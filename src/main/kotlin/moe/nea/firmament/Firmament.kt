/*
 * Firmament is a Hypixel Skyblock mod for modern Minecraft versions
 * Copyright (C) 2023 Linnea Gräf
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package moe.nea.firmament

import com.mojang.brigadier.CommandDispatcher
import dev.architectury.event.events.client.ClientTickEvent
import io.ktor.client.HttpClient
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import java.nio.file.Files
import java.nio.file.Path
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.loader.api.Version
import net.fabricmc.loader.api.metadata.ModMetadata
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.coroutines.EmptyCoroutineContext
import net.minecraft.command.CommandRegistryAccess
import net.minecraft.util.Identifier
import moe.nea.firmament.commands.registerFirmamentCommand
import moe.nea.firmament.dbus.FirmamentDbusObject
import moe.nea.firmament.events.TickEvent
import moe.nea.firmament.features.FeatureManager
import moe.nea.firmament.repo.HypixelStaticData
import moe.nea.firmament.repo.RepoManager
import moe.nea.firmament.util.SBData
import moe.nea.firmament.util.data.IDataHolder

object Firmament {
    const val MOD_ID = "firmament"

    val DEBUG = System.getProperty("firmament.debug") == "true"
    val DATA_DIR: Path = Path.of(".firmament").also { Files.createDirectories(it) }
    val CONFIG_DIR: Path = Path.of("config/firmament").also { Files.createDirectories(it) }
    val logger: Logger = LogManager.getLogger("Firmament")
    private val metadata: ModMetadata by lazy {
        FabricLoader.getInstance().getModContainer(MOD_ID).orElseThrow().metadata
    }
    val version: Version by lazy { metadata.version }

    val json = Json {
        prettyPrint = DEBUG
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val httpClient by lazy {
        HttpClient {
            install(ContentNegotiation) {
                json(json)
            }
            install(ContentEncoding) {
                gzip()
                deflate()
            }
            install(UserAgent) {
                agent = "Firmament/$version"
            }
            if (DEBUG)
                install(Logging) {
                    level = LogLevel.INFO
                }
            install(HttpCache)
        }
    }

    val globalJob = Job()
    val dbusConnection = DBusConnectionBuilder.forSessionBus()
        .build()
    val coroutineScope =
        CoroutineScope(EmptyCoroutineContext + CoroutineName("Firmament")) + SupervisorJob(globalJob)

    private fun registerCommands(
        dispatcher: CommandDispatcher<FabricClientCommandSource>,
        @Suppress("UNUSED_PARAMETER")
        ctx: CommandRegistryAccess
    ) {
        registerFirmamentCommand(dispatcher)
    }

    @JvmStatic
    fun onInitialize() {
    }

    @JvmStatic
    fun onClientInitialize() {
        dbusConnection.requestBusName("moe.nea.firmament")
        var tick = 0
        ClientTickEvent.CLIENT_POST.register(ClientTickEvent.Client { instance ->
            TickEvent.publish(TickEvent(tick++))
        })
        dbusConnection.exportObject(FirmamentDbusObject)
        IDataHolder.registerEvents()
        RepoManager.initialize()
        SBData.init()
        FeatureManager.autoload()
        HypixelStaticData.spawnDataCollectionLoop()
        ClientCommandRegistrationCallback.EVENT.register(this::registerCommands)
        ClientLifecycleEvents.CLIENT_STOPPING.register(ClientLifecycleEvents.ClientStopping {
            runBlocking {
                logger.info("Shutting down NEU coroutines")
                globalJob.cancel()
            }
        })

    }

    fun identifier(path: String) = Identifier(MOD_ID, path)
}
