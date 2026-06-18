package com.company.aitest.tools;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.List;

final class RandomData {
    static final SecureRandom RANDOM = new SecureRandom();
    static final List<String> FAMILY_NAMES = List.of("赵", "钱", "孙", "李", "周", "吴", "郑", "王", "冯", "陈", "刘", "杨", "黄", "何");
    static final List<String> GIVEN_NAMES = List.of("子涵", "一诺", "思远", "嘉懿", "明轩", "梓萱", "雨桐", "浩然", "若曦", "景行");
    static final List<String> EN_FIRST_NAMES = List.of("James", "Olivia", "Liam", "Emma", "Noah", "Ava", "Lucas", "Sophia");
    static final List<String> EN_LAST_NAMES = List.of("Smith", "Johnson", "Brown", "Taylor", "Anderson", "Miller", "Wilson", "Clark");
    static final List<String> CITIES = List.of("北京市朝阳区", "上海市浦东新区", "深圳市南山区", "杭州市西湖区", "成都市高新区", "南京市建邺区");
    static final List<String> ROADS = List.of("科技路", "人民路", "建设路", "花园路", "文三路", "创新大道");

    private RandomData() {
    }

    static String pick(List<String> values) {
        return values.get(RANDOM.nextInt(values.size()));
    }

    static int between(int minInclusive, int maxInclusive) {
        return minInclusive + RANDOM.nextInt(maxInclusive - minInclusive + 1);
    }

    static LocalDate randomDate(LocalDate start, LocalDate end) {
        long days = end.toEpochDay() - start.toEpochDay();
        return start.plusDays(RANDOM.nextLong(days + 1));
    }
}
