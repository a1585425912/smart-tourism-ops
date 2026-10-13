-- 巡检模板默认配置(冰雪景区日常巡检)。items: [{code, name, required, hint}]
INSERT INTO inspection_template (name, category, items) VALUES
('冰雕冰景结构安全巡检', 'ICE_STRUCTURE', '[
  {"code": "ICE_CRACK", "name": "冰体裂缝与结构变形", "required": true, "hint": "重点检查承重冰墙、冰塔底座，裂缝宽度超过2mm或出现倾斜需立即上报"},
  {"code": "ICE_MELT", "name": "冰体融化与表面滴水", "required": true, "hint": "关注午后向阳面与灯具周边，记录融化深度与滴水位置"},
  {"code": "GUARDRAIL", "name": "护栏与警戒线", "required": true, "hint": "护栏牢固无缺失，危险区域警戒线完整"},
  {"code": "ANTISLIP", "name": "通道防滑措施", "required": true, "hint": "防滑垫铺设、撒沙或防滑颗粒是否到位，坡道台阶重点检查"},
  {"code": "LIGHTING", "name": "冰灯照明与线路", "required": true, "hint": "灯具亮度正常，线路无裸露破损，接头防水防冻"},
  {"code": "SIGNAGE", "name": "安全警示标识", "required": false, "hint": "禁止攀爬、小心滑倒等标识清晰可见"}
]'::jsonb),
('游乐设施与滑梯巡检', 'RIDE', '[
  {"code": "SLIDE_SURFACE", "name": "冰滑梯滑道表面", "required": true, "hint": "滑道平整，无凸起、坑洼、裂缝和异物"},
  {"code": "SLIDE_BUFFER", "name": "滑梯终点缓冲区", "required": true, "hint": "缓冲区长度足够，防护垫完好无移位"},
  {"code": "QUEUE_ORDER", "name": "排队秩序与隔离栏", "required": true, "hint": "隔离栏完好，排队区无拥挤推搡，排队时长公示准确"},
  {"code": "OPERATOR", "name": "安全员在岗", "required": true, "hint": "安全员在岗并佩戴标识，按规定间隔放行"},
  {"code": "EQUIP_CHECK", "name": "开机前检查记录", "required": true, "hint": "当日开机前检查记录已填写并签字"},
  {"code": "NOTICE", "name": "游客须知公示", "required": false, "hint": "身高、年龄、健康限制等须知清晰可见"}
]'::jsonb),
('消防与应急通道巡检', 'FIRE', '[
  {"code": "EXTINGUISHER", "name": "灭火器", "required": true, "hint": "压力表指针在绿区、在有效期内，低温环境下无冻结"},
  {"code": "EXIT_CLEAR", "name": "应急通道畅通", "required": true, "hint": "无占用、无积雪堆放、无上锁"},
  {"code": "EXIT_SIGN", "name": "疏散指示与应急照明", "required": true, "hint": "应急照明及疏散指示灯工作正常"},
  {"code": "HYDRANT", "name": "消火栓防冻", "required": true, "hint": "消火栓保温措施到位，无冻裂"},
  {"code": "BROADCAST", "name": "应急广播", "required": false, "hint": "广播测试音清晰，覆盖主要游览区"},
  {"code": "FIRST_AID", "name": "医疗救护点", "required": false, "hint": "急救箱物资齐全，AED 可用，冻伤处置物资充足"}
]'::jsonb),
('卫生与服务设施巡检', 'SERVICE', '[
  {"code": "TOILET", "name": "卫生间", "required": true, "hint": "清洁无异味，供暖正常，地面无结冰"},
  {"code": "WARMING", "name": "取暖点", "required": true, "hint": "取暖设施运行正常，室内温度适宜，无超员"},
  {"code": "TRASH", "name": "垃圾收集", "required": true, "hint": "垃圾桶未满溢，分类标识清楚"},
  {"code": "SNOW_CLEAR", "name": "主路积雪清扫", "required": true, "hint": "主游览路线积雪及时清扫，无暗冰"},
  {"code": "SERVICE_DESK", "name": "游客服务点", "required": false, "hint": "服务人员在岗，咨询、寄存、失物招领正常"},
  {"code": "CATERING", "name": "餐饮摊位卫生", "required": false, "hint": "食品加热保温到位，从业人员健康证公示"}
]'::jsonb),
('视频监控点位巡检', 'VIDEO', '[
  {"code": "CAM_IMAGE", "name": "画面清晰度", "required": true, "hint": "画面无雪花、无黑屏，镜头无结霜覆雪"},
  {"code": "CAM_ANGLE", "name": "监控角度与遮挡", "required": true, "hint": "覆盖重点区域，无冰雕、广告牌等遮挡"},
  {"code": "CAM_POWER", "name": "供电与线缆", "required": true, "hint": "低温环境下供电正常，线缆无破损、无被踩压"},
  {"code": "CAM_HEATER", "name": "防护罩加热除霜", "required": false, "hint": "防护罩加热除霜功能正常"},
  {"code": "CAM_ONLINE", "name": "平台在线状态", "required": true, "hint": "现场状态与平台显示的在线状态一致"}
]'::jsonb);
