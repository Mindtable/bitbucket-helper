package com.mindtable.bitbuckethelper.bootstrap

import com.github.ajalt.clikt.core.main
import com.mindtable.bitbuckethelper.cli.ProductCommandDependencies
import com.typesafe.config.ConfigFactory
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    try {
        val environment = System.getenv()
        val config = ConfigFactory.load("application.conf")
        val socketPath = UnixSocketPathLoader.load(config, environment)
        rootCommand(
            runService = { runConfiguredService(environment) },
            productDependencies = ProductCommandDependencies(socketPath),
        ).main(args)
    } catch (failure: StartupConfigurationException) {
        System.err.println("Configuration error: ${failure.message}")
        exitProcess(2)
    } catch (failure: Throwable) {
        System.err.println("Service startup failed")
        exitProcess(1)
    }
}
