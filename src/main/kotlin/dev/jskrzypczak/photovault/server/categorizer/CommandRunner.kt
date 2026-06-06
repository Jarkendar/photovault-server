package dev.jskrzypczak.photovault.server.categorizer

import java.io.File

fun interface CommandRunner {
    /** Executes [command] via `bash -c` in [workdir], returns the exit code. */
    fun run(command: String, workdir: String): Int
}

val DefaultCommandRunner = CommandRunner { command, workdir ->
    val process = ProcessBuilder("bash", "-c", command)
        .directory(File(workdir))
        .redirectErrorStream(true)
        .start()
    process.inputStream.copyTo(System.out)
    process.waitFor()
}
