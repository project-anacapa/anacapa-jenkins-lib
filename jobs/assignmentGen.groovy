folder("Anacapa Grader/${course_org}")
pipelineJob("Anacapa Grader/${course_org}/assignmentGen") {
  parameters {
    stringParam('lab_name', '', 'The name of the lab to create')
  }
  environmentVariables {
    envs(
      course_org: "${course_org}",
      credentials_id: "${credentials_id}"
    )
  }
  steps {
    shell 'bash -c "echo \\\"Hello World Master!\\\""'
    shell 'bash -c "env"'
  }
}