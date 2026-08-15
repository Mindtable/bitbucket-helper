import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneOffset

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
    alias(libs.plugins.openapi.generator)
    alias(libs.plugins.jooq.codegen)
    application
}

group = "com.mindtable.bitbuckethelper"
version = "0.1.0"

repositories {
    mavenCentral()
}

val liquibaseRuntime by configurations.creating

val migrationDirectory = layout.projectDirectory.dir("src/main/resources/db/migration")
val migrationName = Regex("^V[0-9]{4}__[a-z0-9]+(?:_[a-z0-9]+)*\\.xml$")
val jooqCodegenDirectory = layout.buildDirectory.dir("jooq-codegen")
val jooqCodegenDatabase = jooqCodegenDirectory.map { it.file("bitbucket-helper.sqlite") }
val generatedJooqDirectory = layout.buildDirectory.dir("generated/sources/jooq/main/kotlin")
val generatedBitbucketDirectory = layout.buildDirectory.dir("generated/sources/bitbucket/src/main/kotlin")

kotlin { jvmToolchain(25) }

kotlin.sourceSets.named("main") {
    kotlin.srcDir(generatedJooqDirectory)
    kotlin.srcDir(generatedBitbucketDirectory)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

application {
    mainClass.set("com.mindtable.bitbuckethelper.bootstrap.MainKt")
}

ktor {
    fatJar {
        archiveFileName.set("bitbucket-helper-${project.version}-all.jar")
    }
}

dependencies {
    implementation(libs.clikt)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.jackson)
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation(libs.quartz)
    implementation(libs.liquibase.core)
    implementation(libs.jooq)
    implementation(libs.sqlite.jdbc)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.archunit.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)

    liquibaseRuntime(libs.liquibase.core)
    liquibaseRuntime("info.picocli:picocli:4.7.7")
    liquibaseRuntime(libs.sqlite.jdbc)
    jooqCodegen(libs.sqlite.jdbc)
}

val validateMigrationNames by tasks.registering {
    group = "verification"
    inputs.dir(migrationDirectory)
    doLast {
        val candidates = migrationDirectory.asFile.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name != "AGENTS.md" }
        val invalid = candidates.map { it.name }.filterNot(migrationName::matches)
        check(invalid.isEmpty()) { "Invalid migration filenames: ${invalid.sorted().joinToString()}" }
        val versions = candidates.map { it.name.substring(1, 5) }
        check("0000" !in versions) { "Migration version V0000 is forbidden" }
        val duplicates = versions.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        check(duplicates.isEmpty()) { "Duplicate migration versions: ${duplicates.sorted().joinToString()}" }
    }
}

val prepareJooqCodegenDatabase by tasks.registering(JavaExec::class) {
    group = "jooq"
    dependsOn(validateMigrationNames)
    classpath = liquibaseRuntime
    mainClass.set("liquibase.integration.commandline.LiquibaseCommandLine")
    args(
        "--search-path=/",
        "--url=jdbc:sqlite:${jooqCodegenDatabase.get().asFile.absolutePath}",
        "--driver=org.sqlite.JDBC",
        "--changelog-file=${layout.projectDirectory.file("src/main/resources/db/changelog/db.changelog-master.xml").asFile.absolutePath}",
        "update",
    )
    doFirst {
        Files.createDirectories(jooqCodegenDirectory.get().asFile.toPath())
        Files.deleteIfExists(jooqCodegenDatabase.get().asFile.toPath())
    }
}

jooq {
    configuration {
        jdbc {
            driver = "org.sqlite.JDBC"
            url = "jdbc:sqlite:${jooqCodegenDatabase.get().asFile.absolutePath}"
        }
        generator {
            name = "org.jooq.codegen.KotlinGenerator"
            database { name = "org.jooq.meta.sqlite.SQLiteDatabase" }
            generate {
                isDeprecated = false
                isRecords = true
                isImmutablePojos = false
            }
            target {
                packageName = "com.mindtable.bitbuckethelper.adapter.outbound.persistence.generated"
                directory = generatedJooqDirectory.get().asFile.path
            }
        }
    }
}

tasks.named("jooqCodegen") {
    dependsOn(prepareJooqCodegenDatabase)
}

val canonicalBitbucketOpenApiUrl = "https://api.bitbucket.org/swagger.json"
val canonicalBitbucketOpenApi = layout.projectDirectory.file("specs/bitbucket-cloud/openapi.json")
val canonicalBitbucketOpenApiMetadata = layout.projectDirectory.file("specs/bitbucket-cloud/README.md")

val updateBitbucketOpenApiSpec by tasks.registering {
    group = "maintenance"
    description = "Explicitly downloads and validates the pinned Bitbucket Cloud OpenAPI snapshot."
    doLast {
        val updateDirectory = layout.buildDirectory.dir("openapi-update").get().asFile.toPath()
        Files.createDirectories(updateDirectory)
        val candidate = updateDirectory.resolve("openapi.json.download")
        URI.create(canonicalBitbucketOpenApiUrl).toURL().openStream().use { input ->
            Files.copy(input, candidate, REPLACE_EXISTING)
        }

        @Suppress("UNCHECKED_CAST")
        val root = JsonSlurper().parse(candidate.toFile()) as Map<String, Any?>
        val swaggerVersion = root["swagger"] as? String
        check(swaggerVersion == "2.0") {
            "Candidate must be Swagger/OpenAPI 2.0; found: $swaggerVersion"
        }
        val paths = root["paths"] as? Map<*, *>
        val userPath = paths?.get("/user") as? Map<*, *>
        check(userPath?.get("get") is Map<*, *>) {
            "Candidate must contain GET /user"
        }

        val checksum = MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(candidate))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        val snapshotPath = canonicalBitbucketOpenApi.asFile.toPath()
        Files.createDirectories(snapshotPath.parent)
        Files.move(candidate, snapshotPath, ATOMIC_MOVE, REPLACE_EXISTING)
        canonicalBitbucketOpenApiMetadata.asFile.writeText(
            """# Bitbucket Cloud OpenAPI Snapshot

- Canonical source: `$canonicalBitbucketOpenApiUrl`
- Retrieved (UTC): `${LocalDate.now(ZoneOffset.UTC)}`
- SHA-256: `$checksum`
- Source format: `Swagger/OpenAPI 2.0`
- OpenAPI Generator validation version: `7.24.0`
- Generated client library: `jvm-ktor`
- Reduced-spec compatibility: `definitions.object.additionalProperties` omitted; canonical snapshot unchanged

## Update and review procedure

1. Run `./gradlew updateBitbucketOpenApiSpec` explicitly; normal builds never run it.
2. Review the complete `openapi.json` diff, especially `GET /user` and recursively referenced schemas.
3. Run `./gradlew clean check` to regenerate and compile the selected client.
4. Inspect `build/generated/sources/bitbucket/src/main/kotlin` before committing the snapshot and metadata together.
""",
        )
    }
}

fun collectSwaggerRefs(node: Any?, destination: MutableSet<String>) {
    when (node) {
        is Map<*, *> -> node.forEach { (key, value) ->
            if (key == "\$ref" && value is String) {
                val supportedPrefixes = setOf("#/definitions/", "#/parameters/", "#/responses/")
                check(supportedPrefixes.any { value.startsWith(it) }) {
                    "External or unsupported OpenAPI reference is not allowed: $value"
                }
                destination += value
            } else {
                collectSwaggerRefs(value, destination)
            }
        }
        is Iterable<*> -> node.forEach { collectSwaggerRefs(it, destination) }
    }
}

val prepareBitbucketOpenApi by tasks.registering {
    val source = canonicalBitbucketOpenApi
    val target = layout.buildDirectory.file("openapi/bitbucket-current-user.json")
    inputs.file(source)
    outputs.file(target)
    doLast {
        @Suppress("UNCHECKED_CAST")
        val root = JsonSlurper().parse(source.asFile) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val paths = root.getValue("paths") as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val userPath = paths.getValue("/user") as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val selectedGet = LinkedHashMap(userPath.getValue("get") as Map<String, Any?>)
        selectedGet["operationId"] = "getCurrentUser"

        val pending = linkedSetOf<String>()
        val processed = linkedSetOf<String>()
        val copied = linkedMapOf<String, MutableMap<String, Any?>>()
        collectSwaggerRefs(selectedGet, pending)
        while (true) {
            val ref = pending.firstOrNull { it !in processed } ?: break
            processed += ref
            val segments = ref.removePrefix("#/").split('/')
            check(segments.size == 2) { "Unsupported Swagger reference: $ref" }
            @Suppress("UNCHECKED_CAST")
            val referencedEntries = root.getValue(segments[0]) as Map<String, Any?>
            val value = referencedEntries.getValue(segments[1])
            copied.getOrPut(segments[0]) { linkedMapOf() }[segments[1]] = value
            collectSwaggerRefs(value, pending)
        }

        @Suppress("UNCHECKED_CAST")
        val generatedObject = copied.getValue("definitions").getValue("object") as Map<String, Any?>
        check(generatedObject["additionalProperties"] == true) {
            "Expected canonical definitions.object.additionalProperties to be true"
        }
        copied.getValue("definitions")["object"] = LinkedHashMap(generatedObject).apply {
            remove("additionalProperties")
        }

        val reduced = linkedMapOf<String, Any?>(
            "swagger" to root.getValue("swagger"),
            "info" to root.getValue("info"),
            "host" to root["host"],
            "basePath" to root["basePath"],
            "schemes" to root["schemes"],
            "consumes" to root["consumes"],
            "produces" to root["produces"],
            "security" to root["security"],
            "tags" to (root["tags"] as? List<*>)?.filter {
                (it as? Map<*, *>)?.get("name") == "Users"
            },
            "paths" to linkedMapOf("/user" to linkedMapOf("get" to selectedGet)),
            "definitions" to copied["definitions"],
            "parameters" to copied["parameters"],
            "responses" to copied["responses"],
            "securityDefinitions" to root["securityDefinitions"],
        ).filterValues { it != null }
        target.get().asFile.apply {
            parentFile.mkdirs()
            writeText(JsonOutput.prettyPrint(JsonOutput.toJson(reduced)))
        }
    }
}

openApiGenerate {
    generatorName.set("kotlin")
    library.set("jvm-ktor")
    inputSpec.set(layout.buildDirectory.file("openapi/bitbucket-current-user.json").get().asFile.path)
    outputDir.set(layout.buildDirectory.dir("generated/sources/bitbucket").get().asFile.path)
    templateDir.set(layout.projectDirectory.dir("specs/bitbucket-cloud/openapi-templates"))
    packageName.set("com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.generated")
    apiPackage.set("com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.generated.api")
    modelPackage.set("com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.generated.model")
    invokerPackage.set("com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.generated.infrastructure")
    configOptions.set(
        mapOf(
            "dateLibrary" to "java8",
            "serializationLibrary" to "jackson",
            "useCoroutines" to "true",
            "omitGradleWrapper" to "true",
            "hideGenerationTimestamp" to "true",
        ),
    )
}

tasks.named("openApiGenerate") {
    dependsOn(prepareBitbucketOpenApi)
}

tasks.named("compileKotlin") {
    dependsOn("jooqCodegen", "openApiGenerate")
}

tasks.named("compileTestKotlin") {
    dependsOn("jooqCodegen")
}

tasks.named("check") {
    dependsOn(validateMigrationNames)
}

tasks.test {
    dependsOn(tasks.named("buildFatJar"))
    dependsOn(prepareBitbucketOpenApi)
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
