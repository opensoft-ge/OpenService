package ge.opensoft.openservice;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ConfigurationHistoryTest {
    @Test void nextPageNeverLeavesTheAcceptedOffsetRange() {
        assertEquals(1,ConfigurationHistory.nextOffset(0,1,3));
        assertEquals(-1,ConfigurationHistory.nextOffset(0,100,100));
        assertEquals(-1,ConfigurationHistory.nextOffset(0,100,0));
        assertEquals(1000000,ConfigurationHistory.nextOffset(999900,100,1000101));
        assertEquals(-1,ConfigurationHistory.nextOffset(1000000,100,1000101));
        assertEquals(-1,ConfigurationHistory.nextOffset(999999,100,1000101));
    }
}
