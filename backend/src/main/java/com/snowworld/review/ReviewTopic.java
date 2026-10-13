package com.snowworld.review;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** 网评主题受控字典。keywords 供本地规则分析器使用, 模型输出的主题必须属于本字典。 */
public enum ReviewTopic {
    QUEUE("排队等候", List.of("排队", "等了", "等候", "人太多", "人山人海", "拥挤", "挤", "限流", "等待")),
    TICKET("票务价格", List.of("票价", "门票", "票贵", "性价比", "退票", "购票", "检票", "套票", "价格")),
    HYGIENE("卫生环境", List.of("厕所", "卫生间", "垃圾", "卫生", "干净", "脏", "异味")),
    SERVICE("服务态度", List.of("服务", "工作人员", "态度", "热情", "志愿者", "讲解", "导游", "保安")),
    TRAFFIC("交通停车", List.of("停车", "打车", "公交", "地铁", "交通", "堵车", "接驳", "摆渡车")),
    CATERING("餐饮", List.of("餐饮", "吃的", "饭", "小吃", "餐厅", "冰糖葫芦", "红肠", "热饮", "马迭尔", "冰棍")),
    FACILITY("设施设备", List.of("设施", "滑梯", "摩天轮", "电梯", "座椅", "休息区", "暖房", "充电", "寄存", "设备")),
    SAFETY("安全秩序", List.of("安全", "摔", "滑倒", "秩序", "踩踏", "护栏", "走失", "冰面")),
    SCENERY("景观体验", List.of("冰雕", "雪雕", "灯光", "夜景", "震撼", "好看", "壮观", "漂亮", "拍照", "出片", "美", "风景")),
    COLD("寒冷保暖", List.of("冷", "冻", "零下", "保暖", "暖宝宝", "手机没电", "取暖")),
    OTHER("其他", List.of());

    private final String label;
    private final List<String> keywords;

    ReviewTopic(String label, List<String> keywords) {
        this.label = label;
        this.keywords = keywords;
    }

    public String label() {
        return label;
    }

    public List<String> keywords() {
        return keywords;
    }

    public static Optional<ReviewTopic> parse(String code) {
        if (code == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(t -> t.name().equalsIgnoreCase(code.strip())).findFirst();
    }

    public static String labelOf(String code) {
        return parse(code).map(ReviewTopic::label).orElse(code);
    }
}
