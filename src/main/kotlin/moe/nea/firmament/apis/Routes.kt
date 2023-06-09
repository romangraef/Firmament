package moe.nea.firmament.apis

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.URLProtocol
import io.ktor.http.isSuccess
import io.ktor.http.path
import io.ktor.util.CaseInsensitiveMap
import java.util.UUID
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.nea.firmament.Firmament
import moe.nea.firmament.util.MinecraftDispatcher

object Routes {
    val apiKey = "e721a103-96e0-400f-af2a-73b2a91007b1"
    private val nameToUUID: MutableMap<String, Deferred<UUID?>> = CaseInsensitiveMap()
    private val profiles: MutableMap<UUID, Deferred<Profiles?>> = mutableMapOf()
    private val accounts: MutableMap<UUID, Deferred<PlayerData?>> = mutableMapOf()
    private val UUIDToName: MutableMap<UUID, Deferred<String?>> = mutableMapOf()

    suspend fun getPlayerNameForUUID(uuid: UUID): String? {
        return withContext(MinecraftDispatcher) {
            UUIDToName.computeIfAbsent(uuid) {
                async(Firmament.coroutineScope.coroutineContext) {
                    val response = Firmament.httpClient.get("https://api.ashcon.app/mojang/v2/user/$uuid")
                    if (!response.status.isSuccess()) return@async null
                    val data = response.body<AshconNameLookup>()
                    launch(MinecraftDispatcher) {
                        nameToUUID[data.username] = async { data.uuid }
                    }
                    data.username
                }
            }
        }.await()
    }

    suspend fun getUUIDForPlayerName(name: String): UUID? {
        return withContext(MinecraftDispatcher) {
            nameToUUID.computeIfAbsent(name) {
                async(Firmament.coroutineScope.coroutineContext) {
                    val response = Firmament.httpClient.get("https://api.ashcon.app/mojang/v2/user/$name")
                    if (!response.status.isSuccess()) return@async null
                    val data = response.body<AshconNameLookup>()
                    launch(MinecraftDispatcher) {
                        UUIDToName[data.uuid] = async { data.username }
                    }
                    data.uuid
                }
            }
        }.await()
    }

    suspend fun getAccountData(uuid: UUID): PlayerData? {
        return withContext(MinecraftDispatcher) {
            accounts.computeIfAbsent(uuid) {
                async(Firmament.coroutineScope.coroutineContext) {
                    val response = Firmament.httpClient.get {
                        url {
                            protocol = URLProtocol.HTTPS
                            host = "api.hypixel.net"
                            path("player")
                            parameter("key", apiKey)
                            parameter("uuid", uuid)
                        }
                    }
                    if (!response.status.isSuccess()) {
                        launch(MinecraftDispatcher) {
                            @Suppress("DeferredResultUnused")
                            accounts.remove(uuid)
                        }
                        return@async null
                    }
                    response.body<PlayerResponse>().player
                }
            }
        }.await()
    }

    suspend fun getProfiles(uuid: UUID): Profiles? {
        return withContext(MinecraftDispatcher) {
            profiles.computeIfAbsent(uuid) {
                async(Firmament.coroutineScope.coroutineContext) {
                    val response = Firmament.httpClient.get {
                        url {
                            protocol = URLProtocol.HTTPS
                            host = "api.hypixel.net"
                            path("skyblock", "profiles")
                            parameter("key", apiKey)
                            parameter("uuid", uuid)
                        }
                    }
                    if (!response.status.isSuccess()) {
                        launch(MinecraftDispatcher) {
                            @Suppress("DeferredResultUnused")
                            profiles.remove(uuid)
                        }
                        return@async null
                    }
                    response.body<Profiles>()
                }
            }
        }.await()
    }

}
