package com.ruoyi.system.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiArtifactNamesTest
{
    @Test
    void unknownNameIsNotKnownAndSpecIsNull()
    {
        assertFalse(UiArtifactNames.isKnown("not.registered"));
        assertNull(UiArtifactNames.spec("not.registered"));
        assertNull(UiArtifactNames.spec(null));
    }

    @Test
    void kbReferencesSpecMatchesRegistry()
    {
        assertTrue(UiArtifactNames.isKnown(UiArtifactNames.KB_REFERENCES));
        UiArtifactNames.Spec spec = UiArtifactNames.spec(UiArtifactNames.KB_REFERENCES);
        assertNotNull(spec);
        assertEquals(2, spec.schemaVersion());
        assertEquals(16 * 1024, spec.maxPayloadChars());
        assertEquals(UiArtifactNames.MergePolicy.MERGE, spec.mergePolicy());
        assertEquals(UiArtifactNames.Persistence.SESSION, spec.persistence());
        assertEquals(UiArtifactNames.Scope.RUN, spec.scope());
        assertEquals(0, spec.minIntervalMs());
    }

    @Test
    void runTokenUsageSpecIsEphemeralAndThrottled()
    {
        assertTrue(UiArtifactNames.isKnown(UiArtifactNames.RUN_TOKEN_USAGE));
        UiArtifactNames.Spec spec = UiArtifactNames.spec(UiArtifactNames.RUN_TOKEN_USAGE);
        assertNotNull(spec);
        assertEquals(1, spec.schemaVersion());
        assertEquals(2 * 1024, spec.maxPayloadChars());
        assertEquals(UiArtifactNames.MergePolicy.REPLACE, spec.mergePolicy());
        assertEquals(UiArtifactNames.Persistence.NONE, spec.persistence());
        assertEquals(UiArtifactNames.Scope.RUN, spec.scope());
        assertEquals(500, spec.minIntervalMs());
    }
}
