package com.mypropertyfact.estate.automation.discovery;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class Magicbricks99acresSearchTest {
 @Test void titleParsingIsProvisional(){assertEquals("SG Orchard",Magicbricks99acresSearch.extractName("SG Orchard in Ghaziabad: Price"));}
 @Test void domainsAreStrict(){assertFalse(DiscoveryScreeningRules.allowedSource("https://evil.magicbricks.com.attacker.net/project"));}
}
