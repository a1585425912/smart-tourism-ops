package com.snowworld.common;

import java.util.List;

public record PageResult<T>(List<T> items, long total, int page, int size) {

    public static final int MAX_SIZE = 200;

    public static int clampSize(Integer size) {
        if (size == null || size <= 0) {
            return 20;
        }
        return Math.min(size, MAX_SIZE);
    }

    public static int clampPage(Integer page) {
        return page == null || page < 1 ? 1 : page;
    }

    public static int offset(int page, int size) {
        return (page - 1) * size;
    }
}
