package com.snowworld.scenic;

import com.snowworld.audit.AuditService;
import com.snowworld.auth.ScopeService;
import com.snowworld.common.ApiException;
import com.snowworld.common.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ScenicController {

    private final SiteMapper siteMapper;
    private final ZoneMapper zoneMapper;
    private final ScopeService scope;
    private final AuditService audit;

    public ScenicController(SiteMapper siteMapper, ZoneMapper zoneMapper, ScopeService scope, AuditService audit) {
        this.siteMapper = siteMapper;
        this.zoneMapper = zoneMapper;
        this.scope = scope;
        this.audit = audit;
    }

    public record SiteUpdate(@NotBlank @Size(max = 64) String name, @NotBlank @Size(max = 32) String shortName,
                             @NotNull @Positive Integer capacity,
                             @NotNull @DecimalMin("0.1") @DecimalMax("1.0") BigDecimal warnRatio,
                             @Size(max = 200) String seasonNote, @Size(max = 2000) String description,
                             @NotBlank @Pattern(regexp = "OPEN|CLOSED|SEASONAL") String status) {
    }

    public record ZoneRequest(@Size(max = 32) @Pattern(regexp = "[A-Za-z0-9_-]*") String code,
                              @NotBlank @Size(max = 64) String name,
                              @NotBlank @Pattern(regexp = "AREA|ENTRANCE|ATTRACTION|SERVICE|PARKING|COMMERCIAL") String zoneType,
                              @NotNull Map<String, Object> boundary,
                              @Positive Integer capacity,
                              @DecimalMin("0.1") @DecimalMax("1.0") BigDecimal warnRatio,
                              @Size(max = 200) String countSource, @Size(max = 200) String remark,
                              Integer version) {
    }

    @GetMapping("/scenic-sites")
    @PreAuthorize("hasAuthority('SITE_READ')")
    public List<SiteMapper.SiteRow> sites() {
        List<Long> allowed = scope.allowedSiteIds();
        return siteMapper.findAll().stream().filter(s -> allowed.contains(s.getId())).toList();
    }

    @GetMapping("/scenic-sites/{id}")
    @PreAuthorize("hasAuthority('SITE_READ')")
    public SiteMapper.SiteRow site(@PathVariable long id) {
        scope.requireSite(id);
        return siteMapper.findById(id);
    }

    @PatchMapping("/scenic-sites/{id}")
    @PreAuthorize("hasAuthority('SITE_WRITE')")
    public SiteMapper.SiteRow updateSite(@PathVariable long id, @Valid @RequestBody SiteUpdate req) {
        scope.requireSite(id);
        SiteMapper.SiteRow row = siteMapper.findById(id);
        row.setName(req.name());
        row.setShortName(req.shortName());
        row.setCapacity(req.capacity());
        row.setWarnRatio(req.warnRatio());
        row.setSeasonNote(req.seasonNote());
        row.setDescription(req.description());
        row.setStatus(req.status());
        siteMapper.update(row);
        audit.record("SITE_UPDATE", "SITE", String.valueOf(id), id, "OK", null);
        return siteMapper.findById(id);
    }

    @GetMapping("/scenic-sites/{id}/zones")
    @PreAuthorize("hasAuthority('SITE_READ')")
    public List<ZoneMapper.ZoneRow> zones(@PathVariable long id) {
        scope.requireSite(id);
        return zoneMapper.findBySite(id);
    }

    @PostMapping("/scenic-sites/{id}/zones")
    @PreAuthorize("hasAuthority('SITE_WRITE')")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public ZoneMapper.ZoneRow createZone(@PathVariable long id, @Valid @RequestBody ZoneRequest req) {
        scope.requireSite(id);
        ZoneMapper.ZoneRow row = toRow(req);
        row.setSiteId(id);
        if (row.getCode() == null || row.getCode().isBlank()) {
            row.setCode("Z" + System.currentTimeMillis() % 100000000);
        }
        zoneMapper.insert(row, ScopeService.currentUser().getId());
        audit.record("ZONE_CREATE", "ZONE", String.valueOf(row.getId()), id, "OK", row.getName());
        return zoneMapper.findById(row.getId());
    }

    @PatchMapping("/zones/{zoneId}")
    @PreAuthorize("hasAuthority('SITE_WRITE')")
    @Transactional
    public ZoneMapper.ZoneRow updateZone(@PathVariable long zoneId, @Valid @RequestBody ZoneRequest req) {
        ZoneMapper.ZoneRow existing = requireZone(zoneId);
        if (req.version() == null) {
            throw ApiException.badRequest("更新区域需要提供 version");
        }
        ZoneMapper.ZoneRow row = toRow(req);
        row.setId(zoneId);
        row.setVersion(req.version());
        if (zoneMapper.update(row) == 0) {
            throw new ApiException(ErrorCode.CONFLICT);
        }
        audit.record("ZONE_UPDATE", "ZONE", String.valueOf(zoneId), existing.getSiteId(), "OK", row.getName());
        return zoneMapper.findById(zoneId);
    }

    @DeleteMapping("/zones/{zoneId}")
    @PreAuthorize("hasAuthority('SITE_WRITE')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteZone(@PathVariable long zoneId) {
        ZoneMapper.ZoneRow existing = requireZone(zoneId);
        zoneMapper.deactivate(zoneId);
        audit.record("ZONE_DEACTIVATE", "ZONE", String.valueOf(zoneId), existing.getSiteId(), "OK", existing.getName());
    }

    private ZoneMapper.ZoneRow requireZone(long zoneId) {
        ZoneMapper.ZoneRow zone = zoneMapper.findById(zoneId);
        if (zone == null || !Boolean.TRUE.equals(zone.getActive())) {
            throw ApiException.notFound("区域");
        }
        scope.requireSite(zone.getSiteId());
        return zone;
    }

    private ZoneMapper.ZoneRow toRow(ZoneRequest req) {
        String geojson = GeoJson.write(req.boundary());
        String problem;
        try {
            problem = siteMapper.validatePolygon(geojson);
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCode.INVALID_GEOMETRY, "无法解析 GeoJSON 多边形");
        }
        if (problem != null) {
            throw new ApiException(ErrorCode.INVALID_GEOMETRY, "区域多边形不合法: " + problem);
        }
        ZoneMapper.ZoneRow row = new ZoneMapper.ZoneRow();
        row.setCode(req.code());
        row.setName(req.name());
        row.setZoneType(req.zoneType());
        row.setBoundary(geojson);
        row.setCapacity(req.capacity());
        row.setWarnRatio(req.warnRatio() == null ? new BigDecimal("0.8") : req.warnRatio());
        row.setCountSource(req.countSource());
        row.setRemark(req.remark());
        return row;
    }
}
