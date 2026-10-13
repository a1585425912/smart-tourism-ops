package com.snowworld.file;

import com.snowworld.common.SnowProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.springframework.stereotype.Service;

/** 本地目录存储。objectKey 由服务端生成, 解析后仍校验路径不越出根目录。 */
@Service
public class LocalStorageService implements StorageService {

    private final Path root;

    public LocalStorageService(SnowProperties props) {
        this.root = Path.of(props.storageDir()).toAbsolutePath().normalize();
    }

    @Override
    public void store(String objectKey, byte[] content) throws IOException {
        Path target = resolve(objectKey);
        Files.createDirectories(target.getParent());
        Files.write(target, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    @Override
    public Path locate(String objectKey) {
        Path target = resolve(objectKey);
        return Files.isRegularFile(target) ? target : null;
    }

    private Path resolve(String objectKey) {
        Path target = root.resolve(objectKey).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("非法的存储路径");
        }
        return target;
    }
}
