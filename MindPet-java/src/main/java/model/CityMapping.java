package model;

import java.util.HashMap;
import java.util.Map;

/**
 * 城市名称映射
 */
public class CityMapping {
    
    private static final Map<String, String> CITY_NAME_MAP = new HashMap<>();
    
    static {
        CITY_NAME_MAP.put("北京", "Beijing");
        CITY_NAME_MAP.put("上海", "Shanghai");
        CITY_NAME_MAP.put("广州", "Guangzhou");
        CITY_NAME_MAP.put("深圳", "Shenzhen");
        CITY_NAME_MAP.put("成都", "Chengdu");
        CITY_NAME_MAP.put("杭州", "Hangzhou");
        CITY_NAME_MAP.put("南京", "Nanjing");
        CITY_NAME_MAP.put("武汉", "Wuhan");
        CITY_NAME_MAP.put("西安", "Xi'an");
        CITY_NAME_MAP.put("重庆", "Chongqing");
        CITY_NAME_MAP.put("天津", "Tianjin");
        CITY_NAME_MAP.put("苏州", "Suzhou");
        CITY_NAME_MAP.put("郑州", "Zhengzhou");
        CITY_NAME_MAP.put("长沙", "Changsha");
        CITY_NAME_MAP.put("沈阳", "Shenyang");
        CITY_NAME_MAP.put("青岛", "Qingdao");
        CITY_NAME_MAP.put("济南", "Jinan");
        CITY_NAME_MAP.put("哈尔滨", "Harbin");
        CITY_NAME_MAP.put("大连", "Dalian");
        CITY_NAME_MAP.put("厦门", "Xiamen");
        CITY_NAME_MAP.put("福州", "Fuzhou");
        CITY_NAME_MAP.put("合肥", "Hefei");
        CITY_NAME_MAP.put("昆明", "Kunming");
        CITY_NAME_MAP.put("南宁", "Nanning");
        CITY_NAME_MAP.put("贵阳", "Guiyang");
        CITY_NAME_MAP.put("南昌", "Nanchang");
        CITY_NAME_MAP.put("太原", "Taiyuan");
        CITY_NAME_MAP.put("石家庄", "Shijiazhuang");
        CITY_NAME_MAP.put("长春", "Changchun");
        CITY_NAME_MAP.put("兰州", "Lanzhou");
        CITY_NAME_MAP.put("呼和浩特", "Hohhot");
        CITY_NAME_MAP.put("乌鲁木齐", "Urumqi");
        CITY_NAME_MAP.put("西宁", "Xining");
        CITY_NAME_MAP.put("银川", "Yinchuan");
        CITY_NAME_MAP.put("拉萨", "Lhasa");
        CITY_NAME_MAP.put("海口", "Haikou");
    }
    
    public static String getEnglishCityName(String chineseName) {
        String trimmedName = chineseName.trim();
        return CITY_NAME_MAP.getOrDefault(trimmedName, trimmedName);
    }
    
    public static boolean isSupportedCity(String chineseName) {
        return CITY_NAME_MAP.containsKey(chineseName.trim());
    }
}