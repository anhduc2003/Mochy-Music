package com.mochyy.mochymusic.module.bridge

import com.mochyy.mochymusic.module.AccountState
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

// the account state is the one part of the api a module app sends back through the bridge, so every
// shape it can take has to survive the trip and a shape from a newer module must not break the host
class WireAccountTest {

    @Test
    fun `every account state survives the wire`() {
        val states = listOf(
            AccountState.SignedOut,
            AccountState.SignedIn("signed in as mochy"),
            AccountState.Expired("the session ended"),
            AccountState.Unreachable("the server didn't answer"),
        )
        states.forEach { state ->
            assertEquals(state, Wire.accountFrom(Wire.accountToJson(state)))
        }
    }

    @Test
    fun `a state a newer module invents reads as signed out`() {
        val invented = buildJsonObject {
            put("state", "somethingNewer")
            put("text", "whatever it means")
        }
        assertEquals(AccountState.SignedOut, Wire.accountFrom(invented))
    }

    @Test
    fun `anything that isn't an object reads as signed out`() {
        assertEquals(AccountState.SignedOut, Wire.accountFrom(Wire.Empty))
    }
}
