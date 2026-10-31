package com.flipauto;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class CutoutRecordTest {
    private static final List<DockGeometry.Box> BOTTOM = List.of(new DockGeometry.Box(351, 682, 369, 66));

    @Test public void roundTripKeepsBoxesForSameSize() {
        String raw = CutoutRecord.encode(1, 0, 720, 748, BOTTOM, 1700000000000L);
        assertEquals(BOTTOM, CutoutRecord.decode(raw, 720, 748));
        assertEquals(1700000000000L, CutoutRecord.timestamp(raw));
    }

    @Test public void roundTripKeepsMultipleBoxes() {
        List<DockGeometry.Box> boxes = List.of(new DockGeometry.Box(0, 0, 66, 120), new DockGeometry.Box(682, 0, 66, 120));
        String raw = CutoutRecord.encode(1, 1, 748, 720, boxes, 5L);
        assertEquals(boxes, CutoutRecord.decode(raw, 748, 720));
    }

    @Test public void differentSizeInvalidatesRecord() {
        String raw = CutoutRecord.encode(1, 0, 720, 748, BOTTOM, 1700000000000L);
        assertNull(CutoutRecord.decode(raw, 748, 720));
        assertNull(CutoutRecord.decode(raw, 720, 720));
    }

    @Test public void malformedRecordsAreRejected() {
        assertNull(CutoutRecord.decode(null, 720, 748));
        assertNull(CutoutRecord.decode("", 720, 748));
        assertNull(CutoutRecord.decode("v1|1|0|720", 720, 748));
        assertNull(CutoutRecord.decode("v2|1|0|720|748|0|351,682,369,66;", 720, 748));
        assertNull(CutoutRecord.decode("v1|1|0|720|748|0|351,682,369;", 720, 748));
        assertNull(CutoutRecord.decode("v1|1|0|720|748|0|;", 720, 748));
    }

    @Test public void outOfBoundsBoxesAreRejected() {
        assertNull(CutoutRecord.decode("v1|1|0|720|748|0|-1,0,10,10;", 720, 748));
        assertNull(CutoutRecord.decode("v1|1|0|720|748|0|700,0,100,10;", 720, 748));
        assertNull(CutoutRecord.decode("v1|1|0|720|748|0|0,0,0,10;", 720, 748));
    }

    @Test public void missingTimestampIsTreatedAsOld() {
        assertEquals(0L, CutoutRecord.timestamp("v1|1|0|720|748|oops|351,682,369,66;"));
        assertEquals(0L, CutoutRecord.timestamp(""));
        assertEquals(0L, CutoutRecord.timestamp(null));
    }
}
