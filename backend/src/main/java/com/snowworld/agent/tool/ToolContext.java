package com.snowworld.agent.tool;

import com.snowworld.auth.Permission;
import com.snowworld.scenic.SiteMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

/**
 * 单次工具调用的上下文, 全部由服务端构造, 模型无法影响。
 *
 * @param allowedSites    任务创建时的范围快照 ∩ 调用时刻用户的实时授权
 * @param permissions     调用时刻用户的实时权限
 * @param knownSourceIds  本任务此前工具调用实际返回过的来源 ID
 */
public record ToolContext(long runId, long userId, List<Long> allowedSites, Set<Permission> permissions,
                          List<SiteMapper.SiteRow> allSites, OffsetDateTime now, int callSeq,
                          Set<String> knownSourceIds) {

    public SiteMapper.SiteRow site(long id) {
        return allSites.stream().filter(s -> s.getId() == id).findFirst().orElseThrow();
    }

    public String allowedSiteNames() {
        return allSites.stream().filter(s -> allowedSites.contains(s.getId()))
                .map(SiteMapper.SiteRow::getName).reduce((a, b) -> a + "、" + b).orElse("无");
    }
}
