package com.leyou.search.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.leyou.common.pojo.PageResult;
import com.leyou.common.utils.JsonUtils;
import com.leyou.common.utils.NumberUtils;
import com.leyou.item.pojo.Brand;
import com.leyou.item.pojo.Category;
import com.leyou.search.client.BrandClient;
import com.leyou.search.client.CategoryClient;
import com.leyou.search.client.SpecificationClient;
import com.leyou.search.pojo.Goods;
import com.leyou.search.pojo.SearchRequest;
import com.leyou.search.pojo.SearchResult;
import com.leyou.search.repository.GoodsRepository;
import org.apache.commons.lang.StringUtils;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.index.query.MatchQueryBuilder;
import org.elasticsearch.index.query.Operator;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.aggregations.Aggregation;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.bucket.histogram.InternalHistogram;
import org.elasticsearch.search.aggregations.bucket.terms.LongTerms;
import org.elasticsearch.search.aggregations.bucket.terms.StringTerms;
import org.elasticsearch.search.aggregations.metrics.stats.InternalStats;
import org.elasticsearch.search.sort.SortBuilder;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchTemplate;
import org.springframework.data.elasticsearch.core.aggregation.AggregatedPage;
import org.springframework.data.elasticsearch.core.query.FetchSourceFilter;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;


@Service
public class SearchService {

    @Autowired
    private GoodsRepository goodsRepository;

    @Autowired
    private CategoryClient categoryClient;

    @Autowired
    private BrandClient brandClient;

    @Autowired
    private ElasticsearchTemplate elasticsearchTemplate;

    @Autowired
    private SpecificationClient specClient;

    private static final Logger logger = LoggerFactory.getLogger(SearchService.class);

    public PageResult<Goods> search(SearchRequest searchRequest) {
        String key = searchRequest.getKey();

        /**
         * 判断是否有搜索条件，如果没有，直接返回null。不允许搜索全部商品
         */
        if (StringUtils.isBlank(key)){
            return null;
        }
        //1.构建查询条件
        NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder();
        MatchQueryBuilder basicQuery = QueryBuilders.matchQuery("all", key).operator(Operator.AND);
        //1.1.对关键字进行全文检索查询
        queryBuilder.withQuery(QueryBuilders.matchQuery("all",key).operator(Operator.AND));
        //1.2.通过sourceFilter设置返回的结果字段，只需要id,skus,subTitle
        queryBuilder.withSourceFilter(new FetchSourceFilter(new String[]{"id","skus","subTitle"},null));
        //1.3.分页和排序
        searchWithPageAndSort(queryBuilder,searchRequest);
        //1.4.聚合
        /**
         * 商品分类聚合名称
         */
        String categoryAggName = "category";
        /**
         * 品牌聚合名称
         */
        String brandAggName = "brand";
        //1.4.1。对商品分类进行聚合
        queryBuilder.addAggregation(AggregationBuilders.terms(categoryAggName).field("cid3"));
        //1.4.2.对品牌进行聚合
        queryBuilder.addAggregation(AggregationBuilders.terms(brandAggName).field("brandId"));
        //2.查询、获取结果
        AggregatedPage<Goods> pageInfo = (AggregatedPage<Goods>) this.goodsRepository.search(queryBuilder.build());
        //3.解析查询结果
        //3.1 分页信息
        Long total = pageInfo.getTotalElements();
        int totalPage = pageInfo.getTotalPages();
        //3.2 商品分类的聚合结果
        List<Category> categories = getCategoryAggResult(pageInfo.getAggregation(categoryAggName));
        //3.3 品牌的聚合结果
        List<Brand> brands = getBrandAggResult(pageInfo.getAggregation(brandAggName));

        List<Map<String,Object>> specs=null;
        if (categories.size()==1){
            specs=getSpec(categories.get(0).getId(),basicQuery);
        }
        //3.封装结果，返回
        return new SearchResult(total, (long)totalPage,pageInfo.getContent(),categories,brands,specs);

    }

    private List<Map<String, Object>> getSpec(Long id, QueryBuilder basicQuery) {
        //不管是全局参数还是sku参数，只要是搜索参数，都根据分类id查询出来
        String specsJSONStr = this.specClient.querySpecificationByCategoryId(id);
        System.out.println("json"+specsJSONStr);
        //1.将规格反序列化为集合
        List<Map<String,Object>> specs = null;
        specs = JsonUtils.nativeRead(specsJSONStr, new TypeReference<List<Map<String, Object>>>() {
        });
        //2.过滤出可以搜索的规格参数名称，分成数值类型、字符串类型
        Set<String> strSpec = new HashSet<>();
        //准备map，用来保存数值规格参数名及单位
        Map<String,String> numericalUnits = new HashMap<>();
        //解析规格
        String searchable = "searchable";
        String numerical = "numerical";
        String k = "k";
        String unit = "unit";
        for (Map<String,Object> spec :specs){
            List<Map<String, Object>> params = (List<Map<String, Object>>) spec.get("params");
            params.forEach(param ->{
                if ((boolean)param.get(searchable)){
                    if (param.containsKey(numerical) && (boolean)param.get(numerical)){
                        numericalUnits.put(param.get(k).toString(),param.get(unit).toString());
                    }else {
                        strSpec.add(param.get(k).toString());
                    }
                }
            });
        }
        //3.聚合计算数值类型的interval
        Map<String,Double> numericalInterval = getNumberInterval(id,numericalUnits.keySet());
        return this.aggForSpec(strSpec,numericalInterval,numericalUnits,basicQuery);
    }
    private Map<String, Double> getNumberInterval(Long id, Set<String> keySet) {
        Map<String,Double> numbericalSpecs = new HashMap<>();
        //准备查询条件
        NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder();
        //不查询任何数据
        queryBuilder.withQuery(QueryBuilders.termQuery("cid3",id.toString())).withSourceFilter(new FetchSourceFilter(new String[]{""},null)).withPageable(PageRequest.of(0,1));
        //添加stats类型的聚合,同时返回avg、max、min、sum、count等
        for (String key : keySet){
            queryBuilder.addAggregation(AggregationBuilders.stats(key).field("specs." + key));
        }
        Map<String,Aggregation> aggregationMap = this.elasticsearchTemplate.query(queryBuilder.build(),
                searchResponse -> searchResponse.getAggregations().asMap()
        );
        for (String key : keySet){
            InternalStats stats = (InternalStats) aggregationMap.get(key);
            double interval = this.getInterval(stats.getMin(),stats.getMax(),stats.getSum());
            numbericalSpecs.put(key,interval);
        }
        return numbericalSpecs;
    }
    private double getInterval(double min, double max, Double sum) {
        //要显示6个区间
        double interval = (max - min) /6.0d;
        //判断是否是小数
        if (sum.intValue() == sum){
            //不是小数，要取整十、整百
            int length = StringUtils.substringBefore(String.valueOf(interval),".").length();
            double factor = Math.pow(10.0,length - 1);
            return Math.round(interval / factor)*factor;
        }else {
            //是小数的话就保留一位小数
            return NumberUtils.scale(interval,1);
        }
    }
    private List<Map<String, Object>> aggForSpec(Set<String> strSpec, Map<String, Double> numericalInterval, Map<String, String> numericalUnits, QueryBuilder basicQuery) {
        List<Map<String,Object>> specs = new ArrayList<>();
        //准备查询条件
        NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder();
        queryBuilder.withQuery(basicQuery);
        //聚合数值类型
        for (Map.Entry<String,Double> entry : numericalInterval.entrySet()) {
            queryBuilder.addAggregation(AggregationBuilders.histogram(entry.getKey()).field("specs." + entry.getKey()).interval(entry.getValue()).minDocCount(1));
        }
        //聚合字符串
        for (String key :strSpec){
            queryBuilder.addAggregation(AggregationBuilders.terms(key).field("specs."+key+".keyword"));
        }

        //解析聚合结果
        Map<String,Aggregation> aggregationMap = this.elasticsearchTemplate.query(queryBuilder.build(), SearchResponse:: getAggregations).asMap();

        //解析数值类型
        for (Map.Entry<String,Double> entry :numericalInterval.entrySet()){
            Map<String,Object> spec = new HashMap<>();
            String key = entry.getKey();
            spec.put("k",key);
            spec.put("unit",numericalUnits.get(key));
            //获取聚合结果
            InternalHistogram histogram = (InternalHistogram) aggregationMap.get(key);
            spec.put("options",histogram.getBuckets().stream().map(bucket -> {
                Double begin = (Double) bucket.getKey();
                Double end = begin + numericalInterval.get(key);
                //对begin和end取整
                if (NumberUtils.isInt(begin) && NumberUtils.isInt(end)){
                    //确实是整数，直接取整
                    return begin.intValue() + "-" + end.intValue();
                }else {
                    //小数，取2位小数
                    begin = NumberUtils.scale(begin,2);
                    end = NumberUtils.scale(end,2);
                    return begin + "-" + end;
                }
            }));
            specs.add(spec);
        }

        //解析字符串类型
        strSpec.forEach(key -> {
            Map<String,Object> spec = new HashMap<>();
            spec.put("k",key);
            StringTerms terms = (StringTerms) aggregationMap.get(key);
            spec.put("options",terms.getBuckets().stream().map((Function<StringTerms.Bucket, Object>) StringTerms.Bucket::getKeyAsString));
            specs.add(spec);
        });
        return specs;
    }


//    解析品牌聚合结果
    private List<Brand> getBrandAggResult(Aggregation aggregation) {
        LongTerms brandAgg = (LongTerms) aggregation;
        List<Long> bids = new ArrayList<>();
        for (LongTerms.Bucket bucket : brandAgg.getBuckets()){
            bids.add(bucket.getKeyAsNumber().longValue());
        }
        //根据品牌id查询品牌
        return this.brandClient.queryBrandByIds(bids);
    }
//    解析商品分类聚合结果
    private List<Category> getCategoryAggResult(Aggregation aggregation){
        try{
            List<Category> categories = new ArrayList<>();
            LongTerms categoryAgg = (LongTerms) aggregation;
            List<Long> cids = new ArrayList<>();
            for (LongTerms.Bucket bucket:categoryAgg.getBuckets()
                 ) {
                cids.add(bucket.getKeyAsNumber().longValue());
            }
//            根据id查询分类名称
            List<String> names = this.categoryClient.queryNameByIds(cids);
            for (int i=0;i<names.size();i++){
                Category c = new Category();
                c.setId(cids.get(i));
                c.setName(names.get(i));
                categories.add(c);
            }
            return categories;
        }catch (Exception e){
            logger.error("分类聚合出现异常",e);
            return null;
        }
    }
//    构建基本查询条件
    private void searchWithPageAndSort(NativeSearchQueryBuilder queryBuilder, SearchRequest request) {
        // 准备分页参数
        int page = request.getPage();
        int size = request.getDefaultSize();

        // 1、分页
        queryBuilder.withPageable(PageRequest.of(page - 1, size));
        // 2、排序
        String sortBy = request.getSortBy();
        Boolean desc = request.getDescending();
        if (StringUtils.isNotBlank(sortBy)) {
            // 如果不为空，则进行排序
            queryBuilder.withSort(SortBuilders.fieldSort(sortBy).order(desc ? SortOrder.DESC : SortOrder.ASC));
        }
    }
}
