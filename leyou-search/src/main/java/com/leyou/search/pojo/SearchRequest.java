package com.leyou.search.pojo;

import java.util.Map;

public class SearchRequest {
    private String key; //搜索条件
    private Integer page; //当前页
    private static final Integer DEFAULT_SIZE=20;//每页大小
    private static final Integer DEFAULT_PAGE=1;//默认页
    private String sortBy;//排序条件

    private Boolean descending;//升序降序

    private Map<String,String> filter;//过滤条件

    public SearchRequest(String key, Integer page) {
        this.key = key;
        this.page = page;
    }

    public String getSortBy() {
        return sortBy;
    }

    public void setSortBy(String sortBy) {
        this.sortBy = sortBy;
    }

    public Boolean getDescending() {
        return descending;
    }

    public void setDescending(Boolean descending) {
        this.descending = descending;
    }

    public Map<String, String> getFilter() {
        return filter;
    }

    public void setFilter(Map<String, String> filter) {
        this.filter = filter;
    }

    public SearchRequest() {
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public Integer getPage() {
        if (page==null)
            return DEFAULT_PAGE;
        return Math.max(DEFAULT_PAGE,page);
    }

    public void setPage(Integer page) {
        this.page = page;
    }

    public static Integer getDefaultSize() {
        return DEFAULT_SIZE;
    }

    public static Integer getDefaultPage() {
        return DEFAULT_PAGE;
    }
}
