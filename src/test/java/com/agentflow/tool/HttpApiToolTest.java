package com.agentflow.tool;

import static org.junit.jupiter.api.Assertions.*;
import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class HttpApiToolTest {
    @Test void rejectsPrivateAndSpecialUseAddresses() throws Exception {
        assertFalse(HttpApiTool.isPublicAddress(InetAddress.getByName("127.0.0.1")));
        assertFalse(HttpApiTool.isPublicAddress(InetAddress.getByName("10.2.3.4")));
        assertFalse(HttpApiTool.isPublicAddress(InetAddress.getByName("169.254.169.254")));
        assertFalse(HttpApiTool.isPublicAddress(InetAddress.getByName("192.0.2.10")));
        assertFalse(HttpApiTool.isPublicAddress(InetAddress.getByName("fc00::1")));
        assertTrue(HttpApiTool.isPublicAddress(InetAddress.getByName("8.8.8.8")));
        assertTrue(HttpApiTool.isPublicAddress(InetAddress.getByName("2606:4700:4700::1111")));
    }
}
