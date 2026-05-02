package com.onsafe.backend

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class OnSafeApplication

fun main(args: Array<String>) {
    runApplication<OnSafeApplication>(*args)
}