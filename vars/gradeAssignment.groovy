// vars/assignment.groovy
import static edu.ucsb.cs.anacapa.pipeline.Lib.*

def call(String spec_file) {
  node {
    checkout()
    validateJSON(spec_file)
    def assignment = parseJSON(readFile(spec_file))
    generateGrades(assignment)
    reportResults(assignment)
  }
}

def checkout() {
  /* Checkout from Source */
  stage ('Checkout') {
    // start with a clean workspace
    step([$class: 'WsCleanup'])
    sh 'ls -al'
    checkout scm
    // save the current directory as the "fresh" start state
    stash name: 'fresh'
  }
}

def validateJSON(String spec_file) {
  /* Make sure the assignment spec conforms to the proper conventions */
  stage('validateJSON') {
    def assignment = parseJSON(readFile(spec_file))
    // TODO: insert validation step here...
    //    * This allows us to guarantee that the object has certain properties
    if (assignment == null) { sh 'fail' }
  }
}

def generateGrades(assignment) {
  /* Generate the build stages to run the tests */
  stage('Generate Testing Stages') {
    // def branches = [:]
    /* for each test group */
    def testables = assignment['testables']
    for (int index = 0; index < testables.size(); index++) {
      def i = index
      def curtest = testables[index]
      /* create a parallel group */
      stage(curtest['test_name']) {
        run_test_group(curtest)
      }
    }

    // parallel branches
  }
}

def save_temp_result(testable, test_case, score) {
  def jstr = jsonString([
    test_group: testable['test_name'],
    test_name: test_case['command'],
    score: score,
    max_score: test_case['points']
  ])
  sh "echo '${jstr}' >> ${temp_results_file_for(testable['test_name'])}"
}

def run_test_group(testable) {
  node('submit') {
    // assume built at first
    def built = true
    // clean up the workspace for this particular test group and start fresh
    step([$class: 'WsCleanup'])
    unstash 'fresh'
    // make sure we're starting from scratch for the partial results
    if (fileExists(temp_results_file_for(testable.test_name))) {
      sh "rm ${temp_results_file_for(testable.test_name)}"
    }

    /* Try to build the binaries for the current test group */
    try {
      // execute the desired build command
      sh testable.build_command
      // save this state so each individual test case can run independently
      stash name: testable.test_name
    } catch (e) {
      // if something went wrong building this test case, assume all
      // test cases will fail
      built = false
      println(e)
    }

    if (built) {
      println("Successfully built!")
      for (int i = 0; i < testable.test_cases.size(); i++) {
        def index = i
        run_test_case(testable, testable.test_cases[index])
      }
    } else {
      println("Build Failed...")
      for (int i = 0; i < testable.test_cases.size(); i++) {
        def index = i
        save_temp_result(testable, testable.test_cases[index], 0)
      }
    }

    // stash the partial results so the master can gather them all in the end
    stash includes: temp_results_file_for(testable.test_name), name: "${slugify(testable.test_name)}_results"
  }
}

def run_test_case(testable, test_case) {
  def command = test_case.command
  // assume perfect score to begin with (we will set it to 0 upon failure)
  def score = test_case.points

  try {
    // refresh the workspace to facilitate test case independence
    step([$class: 'WsCleanup'])
    unstash name: testable.test_name
    // save the output for this test case
    def output_name = slugify("${testable.test_name}_${test_case.command}_output")
    sh "${test_case.command} > ${output_name}"

    if (test_case['expected'].equals('generate')) {
      // TODO: figure out how/when to generate the expected outputs
    } else {
      def diff_source = test_case.diff_source
      if (diff_source.equals('stdout')) {
        diff_source = output_name
      }

      def diff_cmd = "diff ${output_name} ${test_case['expected']} > ${output_name}.diff"
      def ret = sh returnStatus: true, script: diff_cmd

      sh "cat ${output_name}.diff"
      if (ret != 0) {
        score = 0
        archiveArtifacts artifacts: "${output_name}.diff", fingerprint: true
      }
    }
  } catch (e) {
    println("Failed to run test case")
    println(e)
    score = 0 // fail
  } finally {
    save_temp_result(testable, test_case, score)
  }
}

def reportResults(assignment) {
  stage('Report Results') {
    def testables = assignment.testables
    def test_results = [
      assignment_name: assignment['assignment_name'],
      repo: env.JOB_NAME,
      results: []
    ]
    for (int index = 0; index < testables.size(); index++) {
      def i = index
      def curtest = testables[index]
      // gather the partial results from this testable
      unstash "${slugify(curtest.test_name)}_results"
      def tmp_results = readFile(
        temp_results_file_for(curtest.test_name)
      ).split("\r?\n")
      for(int j = 0; j < tmp_results.size(); j++) {
        def realj = j
        test_results.results << parseJSON(tmp_results[j])
      }
    }
    def name = slugify("${env.JOB_NAME}_test_results")
    def test_results_json = jsonString(test_results, pretty=true)
    println(test_results_json)
    // write out complete test results to a file and archive it
    sh "echo '${test_results_json}' > ${name}.json"
    archiveArtifacts artifacts: "${name}.json", fingerprint: true
    // clean up the workspace for the next build
    step([$class: 'WsCleanup'])
  }
}

def temp_results_file_for(name) {
  return ".anacapa.tmp_results_${slugify(name)}"
}
