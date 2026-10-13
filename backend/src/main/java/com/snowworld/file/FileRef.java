package com.snowworld.file;

/** 前端使用的文件引用。url 指向需鉴权的下载接口。 */
public record FileRef(long id, String name, String contentType, long size, String url) {

    public static FileRef of(FileMapper.FileRow row) {
        String name = row.getOriginalName() == null || row.getOriginalName().isBlank()
                ? "file-" + row.getId() : row.getOriginalName();
        return new FileRef(row.getId(), name, row.getContentType(), row.getSizeBytes(), "/api/v1/files/" + row.getId());
    }
}
