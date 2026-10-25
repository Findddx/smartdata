package io.smartdata.foundation.workflow

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class WorkflowWorkerApplication

fun main(args: Array<String>) {
    runApplication<WorkflowWorkerApplication>(*args)
}
