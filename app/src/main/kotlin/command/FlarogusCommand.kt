package flarogus.command

import dev.kord.core.entity.Message
import flarogus.Vars
import flarogus.command.parser.CommandArgumentParser
import flarogus.multiverse.Multiverse
import flarogus.multiverse.Log
import flarogus.util.*
import kotlinx.coroutines.delay
import ktsinterface.launch

typealias CommandAction<R> = suspend Callback<R>.() -> Unit
typealias CommandCheck = suspend (message: Message?, args: String) -> String?

open class FlarogusCommand<R>(name: String) {
	val name = name.trim().replace(" ", "_")
	var action: CommandAction<R>? = null
	var arguments: Arguments? = null

	/** If any of the checks returns a string, it is considered that this command cannot be executed. The returned string is the reason. */
	val checks = ArrayList<CommandCheck>()
	/** Time in milliseconds after which an invalid invocation of this command gets deleted. Values less than 0 prevent the deletion. */
	var errorDeleteDelay = 15000L
	/** Whether to perform substitutions while parsing this command. */
	var performSubstitutions = true

	/** The parent command tree. May be null if the command is a root command. */
	var parent: TreeCommand? = null
		set(parent) {
			var check = parent
			do {
				if (check == this) throw RuntimeException("A command tree cannot be recursive")
				check = parent!!.parent
			} while (check != null)

			field = parent
		}
	var description: String = "No description"
	var hidden = false

	/** Internal field, do not modify. */
	var requiredArguments = 0

	init {
		allCommands.add(this)
	}

	open fun action(action: CommandAction<R>) {
		this.action = action
	}

	/** Adds a check to this command */
	open fun check(check: CommandCheck) {
		checks.add(check)
	}

	/** Adds a check that doesn't allow a user to execute this command if they're not a superuser. */
	open fun adminOnly() = check { m, _ -> if (m != null && m.author.isSuperuser()) null else "this command can only be executed by admins" }

	/** Adds a check that doesn't allow a user to execute this command unless they're a moderator */
	open fun modOnly() = check { m, _ -> if (m != null && m.author.isModerator()) null else "this command can only be executed by moderators" }

	/** Adds a check that filters bot / webhook users. */
	open fun noBots() = check { m, _ -> if (m == null || (m.author != null && !m.author!!.isBot)) null else "bot users can't execute this command" }

	/** Adds a check that filters invocations with no originalMessage, making it discord-only. */
	open fun discordOnly() = check { m, _ -> if (m != null) null else "this command can not be executed outside of discord" }

	/** Returns a summary description of command's arguments or an empty string if it has no arguments. */
	open fun summaryArguments(): String = arguments?.let {
		// bless functional programming patterns.
		(it.flags + it.positional).joinToString(" ")
	} ?: ""

	inline fun arguments(builder: Arguments.() -> Unit) {
		if (arguments == null) arguments = Arguments()
		arguments!!.apply(builder)

		//ensure they follow the "required-optional" order
		var opt = false
		arguments!!.positional.forEach {
			if (opt && it.mandatory) throw IllegalArgumentException("Mandatory arguments must come first")
			if (!it.mandatory) opt = true
		}

		updateArgumentCount()
	}

	open suspend operator fun invoke(
		message: Message?,
		argsOverride: String, 
		replyResult: Boolean = true,
		parentCallback: Callback<*>? = null
	): Callback<R> {
		return Callback<R>(this, argsOverride, message).also {
			it.replyResult = replyResult
			it.parentCallback = parentCallback
			useCallback(it)
		}	
	}

	/** Invokes this command for a message and returns the result of this command (if there's any) */
	open suspend operator fun invoke(args: String): R? {
		return Callback<R>(this, args).also { 
			it.replyResult = false
			useCallback(it)
		}.result
	}

	/** Invokes this command for a messagee */
	open suspend operator fun invoke(message: Message): Callback<R> = invoke(message, message.content)

	/** 
	 * Executes this command with the given callback, inflating its argument list.
	 * If the callback has an associated message, reports any errors by replying to it.
	 * Otherwise, any errors are rethrown.
	 */
	open suspend fun useCallback(callback: Callback<R>) {
		try {
			performChecks(callback)

			callback.command = this
			CommandArgumentParser(callback, this).also {
				it.performSubstitutions = performSubstitutions
			}.parse()
			callback.postprocess()
			
			action?.invoke(callback)

			if (!callback.hasResponded && callback.result == null && callback.replyResult) {
				callback.reply("Command executed with no output.")
			}
		} catch (t: Throwable) {
			if (t is CommandException && t.commandName == null) t.commandName = name

			if (callback.originalMessage == null || callback.parentCallback != null) throw t
			if (!callback.replyResult) throw t

			Log.debug { "Exception when executing '${callback.message}': $t" }
			// Log.lifecycle { t.stackTraceToString() }
			replyWithError(callback, t)
		}
	}

	/** Replies to an invalid callback with the exception and schedules its removal. */
	protected open fun replyWithError(callback: Callback<*>, t: Throwable) {
		val reply = callback.reply(t)

		if (errorDeleteDelay >= 0) launch {
			delay(errorDeleteDelay)
			reply?.await()?.delete()
			callback.originalMessage?.delete()
		}
	}

	/** Performs all checks of this command and throws an exception if any of them fail */
	open suspend fun performChecks(callback: Callback<*>) {
		var errors = checks.mapNotNull { it(callback.originalMessage as? Message, callback.message) }

		// check if the user is blacklisted from this command. not using userOf as it can create a new unneccesary entry
		(callback.originalMessage as Message?)?.author?.id?.let { id ->
			val name = getFullName()
			if (Vars.multiverse.users.find { it.discordId == id }?.commandBlacklist?.contains(name) == true) {
				errors += "you're personally blacklisted from this command"
			}
		}

		if (errors.isNotEmpty()) {
			throw IllegalArgumentException("Could not execute this command, because: ${errors.joinToString(", ")}")
		}
	}

	/** @return A name of this command that includes the names of all of it's parents */
	open fun getFullName(): String {
		var current: FlarogusCommand<*>? = this
		return buildString {
			do {
				if (!isEmpty()) insert(0, " ")
				insert(0, current!!.name)
				current = current!!.parent
			} while (current != null)
		}
	}

	fun updateArgumentCount() {
		requiredArguments = arguments?.positional?.fold(0) { t, arg -> if (arg.mandatory) t + 1 else t } ?: 0
	}

	companion object {
		val allCommands = HashSet<FlarogusCommand<*>>(50)

		/** Returns a command whose full name matches is equal to the providen argument */
		fun find(fullName: String, ignoreCase: Boolean = true): FlarogusCommand<*>? {
			return allCommands.find { it.getFullName().trim().equals(fullName, ignoreCase) }
		}
	}
}
