package com.mypropertyfact.estate.automation.discovery;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DiscoveryScreeningRulesTest {
    @Test void sourceAllowListResistsDomainSpoofing() {
        assertTrue(DiscoveryScreeningRules.allowedSource("https://www.magicbricks.com/a"));
        assertTrue(DiscoveryScreeningRules.allowedSource("https://www.99acres.com/a"));
        assertFalse(DiscoveryScreeningRules.allowedSource("https://magicbricks.com.evil.test/a"));
        assertFalse(DiscoveryScreeningRules.allowedSource("http://99acres.com/a"));
        assertFalse(DiscoveryScreeningRules.allowedSource("https://evil.test/?url=magicbricks.com"));
    }
    @Test void permissionAndReraCannotBeAssumed() {
        var c = new DiscoveryCandidate("https://magicbricks.com/a", "A", "B", "Ghaziabad",
                "Ready To Move", "UPRERA123", "https://up-rera.in/record", "");
        assertTrue(DiscoveryScreeningRules.screen(c).contains("IMAGE_PERMISSION_UNCONFIRMED"));
        assertTrue(DiscoveryScreeningRules.screen(c).contains("OFFICIAL_RERA_VERIFICATION_REQUIRED"));
    }
    @Test void statusValidation() {
        assertTrue(DiscoveryScreeningRules.validStatus("Under Construction"));
        assertTrue(DiscoveryScreeningRules.validStatus("New Launched"));
        assertFalse(DiscoveryScreeningRules.validStatus("Sold Out"));
    }
}
