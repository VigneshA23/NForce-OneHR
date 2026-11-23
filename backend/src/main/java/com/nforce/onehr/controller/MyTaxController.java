package com.nforce.onehr.controller;

import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.Principal;
import java.util.List;
import java.util.UUID;

/**
 * US-F10..F14 — Manage Tax, self-service. The employee is always the logged-in user (resolved from the
 * token); no endpoint here takes a user id, so nobody can read or change someone else's tax data.
 * {@code fy} is the financial-year start year (2025 = FY 2025-26); it defaults to the current one.
 */
@RestController
@RequestMapping("/api/me/finances/tax")
@RequiredArgsConstructor
public class MyTaxController {

    private final UserRepository userRepository;
    private final TaxDeclarationService declarations;
    private final PreviousIncomeService previousIncome;
    private final TaxFormsService forms;
    private final TaxConfigService config;

    private User me(Principal p) {
        return userRepository.findByEmail(p.getName()).orElseThrow(() -> new IllegalStateException("Actor not found"));
    }

    static int fyOrCurrent(Integer fy) {
        return fy != null ? fy : FiscalYear.containing(java.time.LocalDate.now(), 4).start().getYear();
    }

    static ResponseEntity<byte[]> file(byte[] data, String name, String type) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .contentType(MediaType.parseMediaType(type == null ? "application/octet-stream" : type)).body(data);
    }

    // ── declarations (F10) ──
    @GetMapping("/declarations")
    public TaxDeclarationService.Overview overview(@RequestParam(required = false) Integer fy, Principal p) {
        return declarations.overview(me(p).getId(), fyOrCurrent(fy));
    }

    @PostMapping("/declarations")
    public TaxDeclarationService.Overview add(@RequestParam(required = false) Integer fy, @RequestBody TaxDeclarationService.Req body, Principal p) {
        UUID id = me(p).getId();
        return declarations.add(id, fyOrCurrent(fy), body, id, false);
    }

    @PutMapping("/declarations/{id}")
    public TaxDeclarationService.Overview update(@PathVariable UUID id, @RequestParam(required = false) Integer fy, @RequestBody TaxDeclarationService.Req body, Principal p) {
        UUID me = me(p).getId();
        return declarations.update(me, fyOrCurrent(fy), id, body, me, false);
    }

    @DeleteMapping("/declarations/{id}")
    public TaxDeclarationService.Overview delete(@PathVariable UUID id, @RequestParam(required = false) Integer fy, Principal p) {
        UUID me = me(p).getId();
        return declarations.delete(me, fyOrCurrent(fy), id, me, false);
    }

    // ── proofs (F11) ──
    @PostMapping("/declarations/{id}/proofs")
    public TaxDeclarationService.Overview uploadProof(@PathVariable UUID id, @RequestParam(required = false) Integer fy,
                                                      @RequestParam("file") MultipartFile file, Principal p) throws IOException {
        return declarations.uploadProof(me(p).getId(), fyOrCurrent(fy), id, file);
    }

    @GetMapping("/declarations/{id}/proofs")
    public List<TaxDeclarationService.ProofMeta> proofs(@PathVariable UUID id, Principal p) {
        return declarations.proofsOf(me(p).getId(), id);
    }

    @GetMapping("/proofs/{proofId}")
    public ResponseEntity<byte[]> proofFile(@PathVariable UUID proofId, Principal p) {
        TaxDeclarationService.ProofFile f = declarations.proofFile(proofId, me(p).getId());
        return file(f.data(), f.fileName(), f.contentType());
    }

    // ── previous employment income (F12) ──
    @GetMapping("/previous-income")
    public PreviousIncomeService.Grid previous(@RequestParam(required = false) Integer fy, Principal p) {
        return previousIncome.grid(me(p).getId(), fyOrCurrent(fy));
    }

    @PutMapping("/previous-income")
    public PreviousIncomeService.Grid savePrevious(@RequestParam(required = false) Integer fy, @RequestBody List<PreviousIncomeService.RowInput> rows, Principal p) {
        UUID id = me(p).getId();
        return previousIncome.save(id, fyOrCurrent(fy), rows, id, false);
    }

    // ── forms (F13) ──
    @GetMapping("/form130/status")
    public java.util.Map<String, Boolean> form130Published(@RequestParam(required = false) Integer fy) {
        return java.util.Map.of("published", forms.isPublished(fyOrCurrent(fy)));
    }

    @GetMapping("/form130")
    public ResponseEntity<byte[]> form130(@RequestParam(required = false) Integer fy, Principal p) {
        TaxFormsService.Pdf pdf = forms.form130(me(p).getId(), fyOrCurrent(fy));
        return file(pdf.data(), pdf.fileName(), "application/pdf");
    }

    @GetMapping("/form124")
    public ResponseEntity<byte[]> form124(@RequestParam(required = false) Integer fy, Principal p) {
        TaxFormsService.Pdf pdf = forms.form124(me(p).getId(), fyOrCurrent(fy));
        return file(pdf.data(), pdf.fileName(), "application/pdf");
    }

    // ── partners (F14) ──
    @GetMapping("/filing")
    public TaxConfigService.Filing filing(@RequestParam(required = false) Integer fy) {
        return config.filing(fyOrCurrent(fy));
    }
}
