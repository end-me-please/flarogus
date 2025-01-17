package flarogus.multiverse.entity

import kotlin.time.*
import kotlinx.serialization.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import dev.kord.rest.builder.message.create.*
import dev.kord.common.entity.*
import dev.kord.core.entity.*
import dev.kord.core.entity.channel.*
import dev.kord.core.behavior.*
import dev.kord.core.behavior.channel.*
import flarogus.*
import flarogus.util.*
import flarogus.multiverse.*
import flarogus.multiverse.state.*

/** 
 * Represents a multiversal guild.
 */
@Serializable
@OptIn(ExperimentalTime::class)
open class MultiversalGuild(
	val discordId: Snowflake
) : MultiversalEntity() {
	@Transient
	var guild: Guild? = null
	@Transient
	val channels = HashSet<TextChannel>()
	@Transient
	val webhooks = HashSet<Webhook>()

	var nameOverride: String? = null
		get() = if (field == null || field!!.isEmpty()) null else field
	val name: String get() = nameOverride ?: guild?.name ?: "unknown guild"

	/** Epoch time of the last sent message. */
	var lastSent = 0L
	/** The total amount of messages sent by this guild. */
	var totalSent = 0
	/** The total amount of messages received by this guild. */
	var totalUserMessages = 0

	/** Whether this guild is allowed to participate in the multiverse */
	var isWhitelisted = false

	/** 
	 * Sends a message into every channel of this guild, optionally invoking a function on every message sent
	 * @param filter filters channels. should return true if the message is to be retranslated into the channel
	 * @param handler invoked whenever a message is being sent.
	 * @param builder builds a message. called whenever a message is being created. Receives the id of the channel a message is being sent to.
	 */
	suspend inline fun send(
		username: String?,
		avatar: String?,
		crossinline filter: (TextChannel) -> Boolean = { true },
		crossinline handler: (Message, Webhook) -> Unit = { _, _ -> },
		crossinline builder: suspend MessageCreateBuilder.(id: Snowflake) -> Unit
	) {
		if (!isWhitelisted) return
		update()
		if (!isValid) return

		webhooks.forEach { webhook ->
			try {
				val channel = channels.find { it.id == webhook.channelId } 
				if (channel == null || !filter(channel)) return@forEach

				webhook.execute(webhook.token!!) {
					builder(webhook.channelId)
					content = content?.take(1999)?.stripEveryone()
					allowedMentions() //forbid all mentions

					this.username = username?.truncate(75) // discord only allows 80.
					avatarUrl = avatar
				}.also { handler(it, webhook) }
			} catch (e: Exception) {
				webhooks.remove(webhook)
				channels.removeAll { it.id == webhook.channelId }
				invalidate()
				Log.error { "An exception has occurred while transmitting a message to '$name': '$e'. Webhook and channel invalidated." }
			}

			yield()
		}
		totalSent++
	}
	
	/** 
	 * Retranslates a message sent by the specified MultiversalUser into every multiversal guild. This method delegates to the Multiverse object.
	 * @see Multiverse#broadcast
	 * @see MultiversalGuild#send
	 */
	suspend inline fun retranslateUserMessage(
		user: MultiversalUser,
		noinline filter: (TextChannel) -> Boolean = { true },
		crossinline builder: suspend MessageCreateBuilder.(id: Snowflake) -> Unit
	): Multimessage {
		if (!isWhitelisted) throw IllegalAccessException("this guild is not whitelisted")
		totalUserMessages++

		lastSent = System.currentTimeMillis()

		return Vars.multiverse.broadcast("${user.name} — $name", user.avatar, filter) {
			builder(it)
			content = content?.stripEveryone()?.revealHypertext()
		}
	}

	override suspend fun updateImpl() {
		if (guild == null || lastUpdate + updateInterval < System.currentTimeMillis()) {
			Log.lifecycle { "fetching guild $discordId..." }
			val newguild = Vars.restSupplier.getGuildOrNull(discordId)

			if (newguild != null) guild = newguild

			// no need to search for channels if it is not whitelisted
			if (isWhitelisted) {
				Log.lifecycle { "exists and whitelisted, searching for webhooks" }
				guild?.channels?.collect {
					if (it !is TextChannel || !isValidChannel(it)) return@collect

					Log.lifecycle { "found ${it.id}" }

					channels.add(it)
					
					try {
						val webhook = it.webhooks.firstOrNull { it.name == webhookName && it.token != null } ?: it.createWebhook(webhookName)
						webhooks.add(webhook)
					} catch (e: Exception) {
						Log.error { "couldn't acquire a webhook for ${it.name} (${it.id}}: $e" }
						channels.remove(it)

						try { 
							it.createMessage("Failed to acquire a webhook for this channel. Next attempt in 10 minutes.") 
						} catch (_: Exception) {}
					}

					yield()
				}
			}

			lastUpdate = System.currentTimeMillis()
		}

		isValid = guild != null // well...

		// remove all invalid webhooks
		webhooks.removeAll { webhook ->
			!channels.any { it.id == webhook.channelId }
		}
	}

	override fun toString() = name

	companion object {
		val updateInterval = 30.minute
		val webhookName = "MultiverseWebhook"

		/** Checks if this channel is a valid multiversal channel */
		suspend fun isValidChannel(channel: TopGuildMessageChannel): Boolean {
			if (!channel.name.contains(Config.multiverseChannelName, true)) return false
			
			val perms = channel.getEffectivePermissions(Vars.botId)
			return Permission.ViewChannel in perms
				&& Permission.SendMessages in perms
				&& Permission.ManageWebhooks in perms
		}
	}
}
