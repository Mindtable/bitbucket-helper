package com.mindtable.bitbuckethelper.bootstrap

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.versionOption
import com.typesafe.config.ConfigFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

private const val APPLICATION_VERSION = "0.1.0"

private class BitbucketHelperCommand : CliktCommand(name = "bitbucket-helper") {
    init { versionOption(APPLICATION_VERSION) }
    override fun run() = Unit
}

private class ServiceCommand : CliktCommand(name = "service") {
    override fun run() = Unit
}

private class ServiceRunCommand(
    private val runService: () -> Unit,
) : CliktCommand(name = "run") {
    override fun run() = runService()
}

fun rootCommand(runService: () -> Unit): CliktCommand =
    BitbucketHelperCommand().subcommands(
        ServiceCommand().subcommands(ServiceRunCommand(runService)),
    )

fun runConfiguredService(environment: Map<String, String> = System.getenv()) {
    val configuration = ServiceConfigurationLoader.load(
        config = ConfigFactory.load("application.conf"),
        environment = environment,
    )
    val shutdown = CountDownLatch(1)
    val closed = AtomicBoolean()
    var runtime: ServiceRuntime? = null
    val closeOnce = {
        if (closed.compareAndSet(false, true)) {
            runtime?.close()
        }
    }
    val shutdownHook = Thread(
        {
            try {
                closeOnce()
            } finally {
                shutdown.countDown()
            }
        },
        "bitbucket-helper-shutdown",
    )
    var hookInstalled = false

    try {
        val activeRuntime = ServiceRuntime.create(configuration)
        runtime = activeRuntime
        Runtime.getRuntime().addShutdownHook(shutdownHook)
        hookInstalled = true
        activeRuntime.start()
        shutdown.await()
    } finally {
        if (hookInstalled) {
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        }
        closeOnce()
    }
}
