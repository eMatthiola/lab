package reiner.completablefuture;

/** 商品详情页的主数据。{@code categoryId} 是 {@code queryRecommend} 的入参，L2 的串行依赖就来自这里。 */
public class Product {

    public final int id;
    public final String name;
    public final int categoryId;

    public Product(int id, String name, int categoryId) {
        this.id = id;
        this.name = name;
        this.categoryId = categoryId;
    }

    @Override
    public String toString() {
        return "Product{id=" + id + ", name=" + name + ", categoryId=" + categoryId + "}";
    }
}
