package com.snowworld.file;

import com.snowworld.auth.AuthUser;
import com.snowworld.auth.ScopeService;
import com.snowworld.common.ApiException;
import com.snowworld.common.ErrorCode;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件上传与读取。类型只认文件头(不信任客户端声明的 Content-Type 与文件名),
 * 存储路径由服务端生成; 读取时校验景区范围。
 */
@Service
public class FileService {

    public static final long MAX_BYTES = 10L * 1024 * 1024;
    public static final Set<String> BIZ_TYPES = Set.of("INSPECTION_RESULT", "INSPECTION_ISSUE", "INSPECTION_RECTIFY");

    private final FileMapper mapper;
    private final StorageService storage;
    private final ScopeService scope;
    private final Clock clock;

    public FileService(FileMapper mapper, StorageService storage, ScopeService scope, Clock clock) {
        this.mapper = mapper;
        this.storage = storage;
        this.scope = scope;
        this.clock = clock;
    }

    public FileRef upload(MultipartFile file, long siteId, String bizType, Long bizId) {
        AuthUser user = ScopeService.currentUser();
        scope.requireSite(siteId);
        if (!BIZ_TYPES.contains(bizType)) {
            throw ApiException.badRequest("bizType 不合法");
        }
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("文件为空");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE);
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("读取上传文件失败");
        }
        ImageType type = ImageType.detect(content);
        if (type == null) {
            throw ApiException.badRequest("仅支持 JPEG、PNG、WEBP 图片");
        }
        LocalDate today = LocalDate.now(clock);
        String key = String.format("%d/%02d/%s.%s", today.getYear(), today.getMonthValue(), UUID.randomUUID(), type.ext);
        try {
            storage.store(key, content);
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INTERNAL, "文件保存失败");
        }
        FileMapper.FileRow row = new FileMapper.FileRow();
        row.setObjectKey(key);
        row.setOriginalName(cleanName(file.getOriginalFilename()));
        row.setContentType(type.contentType);
        row.setSizeBytes((long) content.length);
        row.setSha256(sha256(content));
        row.setSiteId(siteId);
        row.setBizType(bizType);
        row.setBizId(bizId);
        row.setUploaderId(user.getId());
        mapper.insert(row);
        return FileRef.of(row);
    }

    /** 读取前校验景区范围。 */
    public FileMapper.FileRow requireReadable(long id) {
        FileMapper.FileRow row = mapper.findById(id);
        if (row == null) {
            throw ApiException.notFound("文件");
        }
        scope.requireSite(row.getSiteId());
        return row;
    }

    public Path locate(FileMapper.FileRow row) {
        Path path = storage.locate(row.getObjectKey());
        if (path == null) {
            throw ApiException.notFound("文件内容");
        }
        return path;
    }

    /**
     * 校验文件可被挂接到某个业务对象: 必须存在、属于同一景区、由当前操作人上传。
     * 通过后记录归属 bizId。返回去重后的 id 列表。
     */
    public List<Long> requireAttachable(Collection<Long> fileIds, long siteId, long userId, long bizId) {
        if (fileIds == null || fileIds.isEmpty()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(fileIds));
        if (ids.size() > 20) {
            throw ApiException.badRequest("单项附件不能超过 20 个");
        }
        Map<Long, FileMapper.FileRow> rows = mapper.findByIds(ids).stream()
                .collect(Collectors.toMap(FileMapper.FileRow::getId, Function.identity()));
        for (Long id : ids) {
            FileMapper.FileRow row = rows.get(id);
            if (row == null || row.getSiteId() != siteId || row.getUploaderId() != userId) {
                throw ApiException.badRequest("附件 " + id + " 不存在、不属于该景区或不是本人上传");
            }
        }
        mapper.bind(ids, bizId);
        return ids;
    }

    public Map<Long, FileRef> refs(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return mapper.findByIds(List.copyOf(new LinkedHashSet<>(ids))).stream()
                .collect(Collectors.toMap(FileMapper.FileRow::getId, FileRef::of));
    }

    private static String cleanName(String name) {
        if (name == null) {
            return null;
        }
        String base = name.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}\"<>]", "").strip();
        return base.isEmpty() ? null : base.substring(0, Math.min(base.length(), 200));
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    enum ImageType {
        JPEG("image/jpeg", "jpg"), PNG("image/png", "png"), WEBP("image/webp", "webp");

        final String contentType;
        final String ext;

        ImageType(String contentType, String ext) {
            this.contentType = contentType;
            this.ext = ext;
        }

        static ImageType detect(byte[] b) {
            if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
                return JPEG;
            }
            if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                    && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) {
                return PNG;
            }
            if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                    && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
                return WEBP;
            }
            return null;
        }
    }
}
