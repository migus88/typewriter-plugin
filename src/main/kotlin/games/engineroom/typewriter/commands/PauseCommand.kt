package games.engineroom.typewriter.commands

class PauseCommand(private val delay: Long) : Command {

    override fun run() = Thread.sleep(delay)
}
