package io.smartdata.foundation.runtime

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class RuntimeApiApplication

fun main(args: Array<String>) {
    runApplication<RuntimeApiApplication>(*args)
}
