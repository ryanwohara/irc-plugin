package com.irc;

import com.google.gson.Gson;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class OrderStoreTest {
    private final Gson gson = new Gson();

    private static NetworkConfig network(String id) {
        return NetworkConfig.builder().id(id).name(id).host("irc." + id + ".net").build();
    }

    @Test
    public void networkOrderRoundTripsAndIgnoresBlanksAndRepeats() {
        assertEquals(Arrays.asList("a1", "swiftirc", "b2"), OrderStore.parseNetworkOrder(" a1, swiftirc,,b2 ,a1"));
        assertEquals(Collections.emptyList(), OrderStore.parseNetworkOrder(""));
        assertEquals(Collections.emptyList(), OrderStore.parseNetworkOrder(null));
        assertEquals("b2,swiftirc,a1", OrderStore.serializeNetworkOrder(Arrays.asList("b2", "swiftirc", "a1")));
        assertEquals(Arrays.asList("b2", "swiftirc", "a1"),
                OrderStore.parseNetworkOrder(OrderStore.serializeNetworkOrder(Arrays.asList("b2", "swiftirc", "a1"))));
    }

    @Test
    public void listedNetworksComeFirstThenTheRestInTheirNaturalOrder() {
        List<NetworkConfig> natural = Arrays.asList(network("swiftirc"), network("a1"), network("b2"), network("c3"));
        List<NetworkConfig> ordered = OrderStore.applyNetworkOrder(natural, Arrays.asList("b2", "gone", "swiftirc"));
        assertEquals(Arrays.asList(network("b2"), network("swiftirc"), network("a1"), network("c3")), ordered);
    }

    @Test
    public void noSavedNetworkOrderKeepsTheNaturalOrder() {
        List<NetworkConfig> natural = Arrays.asList(network("swiftirc"), network("a1"));
        assertEquals(natural, OrderStore.applyNetworkOrder(natural, Collections.emptyList()));
    }

    @Test
    public void channelOrderRoundTripsKeepingOnlyChannelNames() {
        Map<String, List<String>> order = new LinkedHashMap<>();
        order.put("swiftirc", Arrays.asList("#b", "Luna", "&a", "System", "#c"));
        order.put("a1", Collections.singletonList("#foo"));
        Map<String, List<String>> parsed = OrderStore.parseChannelOrder(gson, OrderStore.serializeChannelOrder(gson, order));
        Map<String, List<String>> expected = new LinkedHashMap<>();
        expected.put("swiftirc", Arrays.asList("#b", "&a", "#c"));
        expected.put("a1", Collections.singletonList("#foo"));
        assertEquals(expected, parsed);
    }

    @Test
    public void blankOrCorruptChannelOrderIsEmpty() {
        assertEquals(Collections.emptyMap(), OrderStore.parseChannelOrder(gson, ""));
        assertEquals(Collections.emptyMap(), OrderStore.parseChannelOrder(gson, null));
        assertEquals(Collections.emptyMap(), OrderStore.parseChannelOrder(gson, "{not json"));
        assertEquals(Collections.emptyMap(), OrderStore.parseChannelOrder(gson, "[\"#a\"]"));
        Map<String, List<String>> parsed = OrderStore.parseChannelOrder(gson, "{\"a1\":[\"#x\",null,\"pm\"],\"b2\":null}");
        assertEquals(Collections.singletonMap("a1", Collections.singletonList("#x")), parsed);
    }

    @Test
    public void sortByOrderPutsSavedNamesFirstIgnoringCase() {
        assertEquals(Arrays.asList("#C", "#a", "#b", "#d"),
                OrderStore.sortByOrder(Arrays.asList("#a", "#b", "#C", "#d"), Arrays.asList("#c", "#gone", "#a")));
        assertEquals(Arrays.asList("#a", "#b"),
                OrderStore.sortByOrder(Arrays.asList("#a", "#b"), Collections.emptyList()));
    }
}
