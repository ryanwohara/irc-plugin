package com.irc;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The server's mode vocabulary, learned from RPL_ISUPPORT (numeric 005).
 *
 * PREFIX=(qaohv)~&@%+ maps membership mode letters to display prefixes positionally, and the
 * position is also the rank (0 = most privileged). CHANMODES=A,B,C,D says which mode letters
 * consume a parameter: A and B always, C only when adding, D never. Membership modes are absent
 * from CHANMODES and always consume one. Knowing this is what keeps "MODE #c +mo bob" from
 * opping the wrong user.
 */
public class ModeSpecTest {

    @Test
    public void defaultsRecognizeTheClassicFivePrefixes() {
        ModeSpec spec = ModeSpec.defaults();
        assertEquals('~', spec.prefixFor('q'));
        assertEquals('&', spec.prefixFor('a'));
        assertEquals('@', spec.prefixFor('o'));
        assertEquals('%', spec.prefixFor('h'));
        assertEquals('+', spec.prefixFor('v'));
    }

    @Test
    public void defaultsRankOwnerAboveVoice() {
        ModeSpec spec = ModeSpec.defaults();
        assertTrue(spec.rankOf('q') < spec.rankOf('o'));
        assertTrue(spec.rankOf('o') < spec.rankOf('v'));
    }

    @Test
    public void unknownModeLetterHasLowestRank() {
        assertEquals(Integer.MAX_VALUE, ModeSpec.defaults().rankOf('Z'));
    }

    @Test
    public void prefixCharMapsBackToItsModeLetter() {
        ModeSpec spec = ModeSpec.defaults();
        assertEquals(Character.valueOf('o'), spec.modeForPrefix('@'));
        assertNull(spec.modeForPrefix('x'));
    }

    @Test
    public void isupportReplacesThePrefixTable() {
        ModeSpec spec = ModeSpec.defaults();
        spec.applyIsupport(Collections.singletonList("PREFIX=(ov)@+"));
        assertEquals(0, spec.rankOf('o'));
        assertFalse("halfop is gone once the server says it has none", spec.isPrefixMode('h'));
    }

    @Test
    public void isupportSkipsTokensThatAreNotKeyValuePairs() {
        ModeSpec spec = ModeSpec.defaults();
        // A real 005 line: leading nick, then tokens, then a trailing description.
        spec.applyIsupport(Arrays.asList("mynick", "PREFIX=(ov)@+", "are supported by this server"));
        assertEquals('@', spec.prefixFor('o'));
        assertFalse(spec.isPrefixMode('h'));
    }

    @Test
    public void unparseablePrefixValueLeavesThePreviousTableIntact() {
        ModeSpec spec = ModeSpec.defaults();
        spec.applyIsupport(Collections.singletonList("PREFIX=garbage"));
        assertEquals('@', spec.prefixFor('o'));
    }

    @Test
    public void prefixModesAlwaysTakeAParameter() {
        ModeSpec spec = ModeSpec.defaults();
        assertTrue(spec.takesParameter('o', true));
        assertTrue(spec.takesParameter('o', false));
        assertTrue(spec.takesParameter('v', false));
    }

    @Test
    public void typeAAndTypeBModesAlwaysTakeAParameter() {
        ModeSpec spec = ModeSpec.defaults();
        assertTrue("b is a list mode", spec.takesParameter('b', true));
        assertTrue(spec.takesParameter('b', false));
        assertTrue("k is a settings mode", spec.takesParameter('k', true));
        assertTrue(spec.takesParameter('k', false));
    }

    @Test
    public void typeCModeTakesAParameterOnlyWhenAdding() {
        ModeSpec spec = ModeSpec.defaults();
        assertTrue(spec.takesParameter('l', true));
        assertFalse(spec.takesParameter('l', false));
    }

    @Test
    public void typeDModeNeverTakesAParameter() {
        ModeSpec spec = ModeSpec.defaults();
        assertFalse(spec.takesParameter('m', true));
        assertFalse(spec.takesParameter('m', false));
    }

    @Test
    public void unknownModeLetterIsAssumedParameterless() {
        // Stated assumption in the spec: type D is the largest class, so it is the safer guess.
        ModeSpec spec = ModeSpec.defaults();
        assertFalse(spec.takesParameter('Z', true));
        assertFalse(spec.takesParameter('Z', false));
    }

    @Test
    public void isupportReplacesTheChanmodesTable() {
        ModeSpec spec = ModeSpec.defaults();
        spec.applyIsupport(Collections.singletonList("CHANMODES=b,k,l,imnpstZ"));
        assertFalse("Z is now a known flag mode", spec.takesParameter('Z', true));
        assertTrue(spec.takesParameter('b', true));
    }
}
