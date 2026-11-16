package com.nforce.onehr.service;

import com.nforce.onehr.dto.asset.AssetBulkValidationResponse;
import com.nforce.onehr.dto.asset.CreateAssetRequest;
import com.nforce.onehr.entity.AssetCategory;
import com.nforce.onehr.entity.Location;
import com.nforce.onehr.repository.AssetCategoryRepository;
import com.nforce.onehr.repository.AssetRepository;
import com.nforce.onehr.repository.LocationRepository;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AssetBulkImportServiceTest {

    @Mock private AssetService assetService;
    @Mock private AssetRepository assetRepo;
    @Mock private AssetCategoryRepository categoryRepo;
    @Mock private LocationRepository locationRepo;
    @InjectMocks private AssetBulkImportService service;

    private static final String ACTOR = "hr@test.com";
    private final UUID hyderabadId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        lenient().when(categoryRepo.findAll()).thenReturn(List.of(AssetCategory.builder().id(1).name("Laptop").build()));
        Location hyd = new Location();
        hyd.setId(hyderabadId); hyd.setName("Hyderabad"); hyd.setActive(true);
        Location old = new Location();
        old.setId(UUID.randomUUID()); old.setName("Old Office"); old.setActive(false);
        lenient().when(locationRepo.findAll()).thenReturn(List.of(hyd, old));
        lenient().when(assetRepo.findAllAssetTagsLowercase()).thenReturn(List.of("lt-existing"));
    }

    private static MockMultipartFile xlsx(String[]... dataRows) throws Exception {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("Assets");
            Row h = s.createRow(0);
            for (int i = 0; i < AssetBulkImportService.HEADERS.length; i++) h.createCell(i).setCellValue(AssetBulkImportService.HEADERS[i]);
            int r = 1;
            for (String[] values : dataRows) {
                Row row = s.createRow(r++);
                for (int i = 0; i < values.length; i++) if (values[i] != null) row.createCell(i).setCellValue(values[i]);
            }
            wb.write(out);
            return new MockMultipartFile("file", "assets.xlsx", "application/octet-stream", out.toByteArray());
        }
    }

    private static List<String> errorColumns(AssetBulkValidationResponse.Row row) {
        return row.errors().stream().map(AssetBulkValidationResponse.CellError::column).toList();
    }

    @Test
    void validate_flagsEachRuleOnTheRightColumn_andAcceptsAValidRow() throws Exception {
        AssetBulkValidationResponse res = service.validate(xlsx(
                // tag, category, brand, model, serial, purchaseDate, cost, warranty, condition, location
                new String[]{"LT-001", "laptop", "Dell", "XPS", "SN1", "2025-01-10", "85000.50", "2027-01-10", "", "hyderabad"},
                new String[]{"lt-001", "Laptop"},                        // duplicate of row 2 (case-insensitive)
                new String[]{"LT-EXISTING", "Laptop"},                   // already in inventory
                new String[]{"", "Phone"},                               // missing tag + unknown category
                new String[]{"LT-005", "Laptop", null, null, null, "10/01/2025", "-5", "2024-01-01", "BROKEN", "Old Office"},
                new String[]{},                                          // blank row: ignored
                new String[]{"LT-007", "Laptop", null, null, null, "2025-02-30"}), ACTOR);

        assertEquals(6, res.totalRows());
        assertEquals(1, res.validRows());
        assertEquals(5, res.invalidRows());

        AssetBulkValidationResponse.Row ok = res.rows().get(0);
        assertTrue(ok.errors().isEmpty());
        assertEquals("Laptop", ok.category());      // normalized to the stored name
        assertEquals("Hyderabad", ok.location());
        assertEquals("GOOD", ok.condition());       // defaulted

        assertEquals(List.of("Asset Tag"), errorColumns(res.rows().get(1)));
        assertTrue(res.rows().get(1).errors().get(0).message().contains("row 2"));
        assertEquals(List.of("Asset Tag"), errorColumns(res.rows().get(2)));
        assertEquals(List.of("Asset Tag", "Category"), errorColumns(res.rows().get(3)));
        // bad date format, negative cost, bad condition, inactive location (warranty-vs-purchase skipped: purchase unparsed)
        assertEquals(List.of("Purchase Date", "Purchase Cost", "Condition", "Location"), errorColumns(res.rows().get(4)));
        assertEquals(8, res.rows().get(5).rowNumber()); // Excel row number survives the skipped blank row
        assertEquals(List.of("Purchase Date"), errorColumns(res.rows().get(5)));
        verify(assetService).requireCanCreate(ACTOR);
        verify(assetService, never()).bulkCreateAssets(anyList(), any()); // validate never writes
    }

    @Test
    void import_withAnyInvalidRow_insertsNothing() throws Exception {
        MockMultipartFile file = xlsx(new String[]{"LT-001", "Laptop"}, new String[]{"LT-002", "Nope"});
        assertThrows(IllegalArgumentException.class, () -> service.importAssets(file, ACTOR));
        verify(assetService, never()).bulkCreateAssets(anyList(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void import_allValid_passesResolvedRequestsToBulkCreate() throws Exception {
        when(assetService.bulkCreateAssets(anyList(), eq(ACTOR))).thenAnswer(i -> ((List<?>) i.getArgument(0)).size());
        var result = service.importAssets(xlsx(
                new String[]{"LT-001", "Laptop", null, null, null, "2025-01-10", "1200", null, "fair", "Hyderabad"},
                new String[]{"LT-002", "Laptop"}), ACTOR);

        assertEquals(2, result.get("imported"));
        ArgumentCaptor<List<CreateAssetRequest>> captor = ArgumentCaptor.forClass(List.class);
        verify(assetService).bulkCreateAssets(captor.capture(), eq(ACTOR));
        CreateAssetRequest first = captor.getValue().get(0);
        assertEquals(1, first.getCategoryId());
        assertEquals(hyderabadId, first.getLocationId());
        assertEquals(LocalDate.of(2025, 1, 10), first.getPurchaseDate());
        assertEquals(0, new BigDecimal("1200").compareTo(first.getPurchaseCost()));
        assertEquals("FAIR", first.getCondition());
        assertEquals("GOOD", captor.getValue().get(1).getCondition());
        assertNull(captor.getValue().get(1).getLocationId());
    }

    @Test
    void template_hasHeadersAndInstructions_andIsAcceptedOnceFilledIn() throws Exception {
        byte[] bytes = service.template(ACTOR);
        verify(assetService).requireCanCreate(ACTOR);
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes)); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            assertNotNull(wb.getSheet("Instructions"));
            Sheet assets = wb.getSheet("Assets");
            // Date columns are real date-formatted columns, not text.
            CellStyle purchaseStyle = assets.getColumnStyle(AssetBulkImportService.PURCHASE_DATE_COL);
            assertEquals("yyyy-mm-dd", purchaseStyle.getDataFormatString());
            assertEquals("yyyy-mm-dd", assets.getColumnStyle(AssetBulkImportService.WARRANTY_COL).getDataFormatString());

            Row row = assets.createRow(1);
            row.createCell(0).setCellValue("LT-100");
            row.createCell(1).setCellValue("Laptop");
            Cell purchase = row.createCell(AssetBulkImportService.PURCHASE_DATE_COL); // as Excel stores a typed date
            purchase.setCellValue(LocalDate.of(2025, 4, 15));
            purchase.setCellStyle(purchaseStyle);
            wb.write(out);
            var res = service.validate(new MockMultipartFile("file", "filled.xlsx", null, out.toByteArray()), ACTOR);
            assertEquals(1, res.validRows());
            assertEquals("2025-04-15", res.rows().get(0).purchaseDate());
        }
    }

    @Test
    void rejectsNonXlsxAndCorruptFilesWithAFriendlyMessage() {
        var csv = new MockMultipartFile("file", "assets.csv", null, "a,b".getBytes());
        assertEquals("Only .xlsx files are supported",
                assertThrows(IllegalArgumentException.class, () -> service.validate(csv, ACTOR)).getMessage());
        var fake = new MockMultipartFile("file", "assets.xlsx", null, "not a zip".getBytes());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> service.validate(fake, ACTOR))
                .getMessage().startsWith("Could not read the file"));
    }
}
