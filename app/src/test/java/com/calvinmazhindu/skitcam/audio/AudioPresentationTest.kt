package com.calvinmazhindu.skitcam.audio

import org.junit.Assert.*
import org.junit.Test

class AudioPresentationTest {
    @Test fun builtInDuplicatesBecomeOneDisplayCategoryWithoutLosingEndpoints() {
        val inputs=listOf(AudioInput(1,InputKind.BuiltIn,"Phone",15),AudioInput(2,InputKind.BuiltIn,"Phone",15))
        val groups=AudioPresentation.groups(inputs)
        assertEquals(1,groups.size);assertEquals(inputs,groups.single().endpoints)
        assertEquals(2,inputs.size)
    }
    @Test fun equallyNamedExternalDevicesKeepSeparateIdentities() {
        val inputs=listOf(AudioInput(1,InputKind.Usb,"Receiver"),AudioInput(2,InputKind.Usb,"Receiver"),AudioInput(3,InputKind.Bluetooth,"Receiver"))
        val groups=AudioPresentation.groups(inputs)
        assertEquals(3,groups.size);assertEquals(3,groups.map { it.label }.distinct().size)
        assertEquals(inputs.toSet(),groups.flatMap { it.endpoints }.toSet())
    }
    @Test fun presentationDoesNotChangeRemovalSafetyOrClaimAnActiveRoute() {
        val usb=AudioInput(7,InputKind.Usb,"Receiver")
        val snapshot=AudioSnapshot(inputs=listOf(usb),permission=true)
        AudioPresentation.groups(snapshot.inputs)
        assertEquals("Microphone · Android automatic",snapshot.title)
        assertTrue(snapshot.observations.isEmpty())
        assertTrue(AudioPolicy.removedExternal(snapshot.inputs,emptyList()))
    }
    @Test fun unknownEndpointsStayAvailableForRawDiagnostics() {
        val inputs=listOf(AudioInput(1,InputKind.Other,"Device",99,"address"),AudioInput(2,InputKind.Other,"Device",100))
        assertEquals(inputs,AudioPresentation.groups(inputs).single().endpoints)
        assertTrue(AudioPresentation.groups(emptyList()).isEmpty())
    }
}
