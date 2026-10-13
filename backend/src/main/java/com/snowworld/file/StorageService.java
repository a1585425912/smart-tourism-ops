package com.snowworld.file;

import java.io.IOException;
import java.nio.file.Path;

/** 文件存储适配器。开发环境使用本地受控目录, 生产可替换为对象存储实现。 */
public interface StorageService {

    /** 以 objectKey 保存内容; key 已存在时失败, 不覆盖。 */
    void store(String objectKey, byte[] content) throws IOException;

    /** 返回可读取的本地路径; 不存在时返回 null。 */
    Path locate(String objectKey);
}
