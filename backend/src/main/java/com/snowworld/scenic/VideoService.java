package com.snowworld.scenic;

import com.snowworld.auth.AuthUser;
import com.snowworld.auth.ScopeService;
import com.snowworld.common.ApiException;
import com.snowworld.common.ErrorCode;
import com.snowworld.common.SnowProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * 视频播放会话。业务服务只负责授权并签发短期会话, 媒体流由视频平台/流媒体服务提供;
 * 设备账号与带密钥的原始地址不下发前端。
 *
 * <p>当前实现为演示适配器(snow.video.provider=demo): 若 data/media 下存在与 streamRef 同名的开放许可视频则回放该文件,
 * 否则返回 DEMO_SYNTHETIC, 由前端渲染明确标注"演示"的模拟画面。接入真实平台时替换 {@link #open} 中的签发逻辑。
 */
@Service
public class VideoService {

    public record PlaybackSession(String status, String type, String url, OffsetDateTime expiresAt,
                                  String message, String dataMode, String attribution) {
    }

    private record Grant(long userId, long deviceId, Path file, Instant expiresAt) {
    }

    private final DeviceMapper deviceMapper;
    private final ScopeService scope;
    private final SnowProperties props;
    private final Clock clock;
    private final Map<String, Grant> grants = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public VideoService(DeviceMapper deviceMapper, ScopeService scope, SnowProperties props, Clock clock) {
        this.deviceMapper = deviceMapper;
        this.scope = scope;
        this.props = props;
        this.clock = clock;
    }

    public PlaybackSession open(long deviceId) {
        AuthUser user = ScopeService.currentUser();
        DeviceMapper.DeviceRow device = deviceMapper.findById(deviceId);
        if (device == null) {
            throw ApiException.notFound("设备");
        }
        scope.requireSite(device.getSiteId());
        if (!"CAMERA".equals(device.getDeviceType())) {
            return new PlaybackSession("INCOMPATIBLE", null, null, null, "该设备不是视频设备", device.getDataMode(), null);
        }
        if (!"ONLINE".equals(device.getStatus())) {
            return new PlaybackSession("OFFLINE", null, null, null,
                    "设备当前状态为 " + device.getStatus() + "，最近状态时间 " + device.getStatusAt(), device.getDataMode(), null);
        }
        if (!"demo".equals(props.video().provider())) {
            throw new ApiException(ErrorCode.VIDEO_UNAVAILABLE, "未配置视频平台适配器");
        }
        purgeExpired();
        Instant expires = Instant.now().plusSeconds(props.video().sessionTtlSeconds());
        OffsetDateTime expiresAt = OffsetDateTime.now(clock).plusSeconds(props.video().sessionTtlSeconds());
        Path media = demoMedia(device.getStreamRef());
        if (media == null) {
            return new PlaybackSession("OK", "DEMO_SYNTHETIC", null, expiresAt,
                    "演示适配器: 未接入真实视频平台, 显示模拟画面", "DEMO", null);
        }
        String token = HexFormat.of().formatHex(random.generateSeed(16));
        grants.put(token, new Grant(user.getId(), deviceId, media, expires));
        return new PlaybackSession("OK", "DEMO_FILE", "/api/v1/video/sessions/" + token + "/media", expiresAt,
                "演示适配器: 播放开放许可的示例视频, 非该点位实时画面", "DEMO", readAttribution(media));
    }

    /** 校验播放令牌: 必须是本人签发且未过期。 */
    public Path resolve(String token) {
        Grant grant = grants.get(token);
        if (grant == null || grant.expiresAt().isBefore(Instant.now())) {
            grants.remove(token);
            throw new ApiException(ErrorCode.FORBIDDEN, "播放会话无效或已过期");
        }
        if (grant.userId() != ScopeService.currentUser().getId()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "播放会话不属于当前用户");
        }
        DeviceMapper.DeviceRow device = deviceMapper.findById(grant.deviceId());
        scope.requireSite(device.getSiteId());
        return grant.file();
    }

    private Path demoMedia(String streamRef) {
        if (streamRef == null || !streamRef.matches("[A-Za-z0-9_-]{1,64}")) {
            return null;
        }
        Path file = Path.of(props.dataDir(), "media", streamRef + ".mp4").toAbsolutePath().normalize();
        return Files.isRegularFile(file) ? file : null;
    }

    private static String readAttribution(Path media) {
        Path credit = media.resolveSibling(media.getFileName().toString().replace(".mp4", ".txt"));
        try {
            return Files.isRegularFile(credit) ? Files.readString(credit).strip() : null;
        } catch (java.io.IOException e) {
            return null;
        }
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        grants.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(now));
    }
}
