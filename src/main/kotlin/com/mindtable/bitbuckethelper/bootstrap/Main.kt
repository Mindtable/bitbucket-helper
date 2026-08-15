package com.mindtable.bitbuckethelper.bootstrap

import com.github.ajalt.clikt.core.main
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    try {
        rootCommand(::runConfiguredService).main(args)
    } catch (failure: StartupConfigurationException) {
        System.err.println("Configuration error: ${failure.message}")
        exitProcess(2)
    } catch (failure: Throwable) {
        System.err.println("Service startup failed")
        exitProcess(1)
    }
}
