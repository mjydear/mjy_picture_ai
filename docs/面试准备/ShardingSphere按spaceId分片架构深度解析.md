# ShardingSphere 按 spaceId 分片架构深度解析

本文用于秋招复习，专门拆解云图库项目中 **ShardingSphere 已引入，并有按 spaceId 分片的算法雏形** 这一块的完整实现逻辑。

相关核心代码：

- `mjy-picture-backend-ddd/pom.xml`
- `mjy-picture-backend-ddd/src/main/resources/application.yml`
- `mjy-picture-backend-ddd/src/main/java/com/yupi/yupicture/shared/sharding/PictureShardingAlgorithm.java`
- `mjy-picture-backend-ddd/src/main/java/com/yupi/yupicture/shared/sharding/DynamicShardingManager.java`
- `mjy-picture-backend-ddd/src/main/java/com/yupi/yupicture/application/service/impl/SpaceApplicationServiceImpl.java`
- `mjy-picture-backend-ddd/sql/create_table.sql`

---

## 1. 先搞懂分库分表是什么

普通项目一开始通常只有一张图片表：

```text
picture
```

所有图片都存在这一张表里。

数据少的时候没问题。

但云图库项目如果用户越来越多，图片越来越多，就可能出现：

```text
picture 表数据量非常大
查询某个空间的图片变慢
写入压力集中在一张表上
索引维护成本越来越高
单表越来越难维护
```

分表就是把一张大表拆成多张小表。

比如：

```text
picture
picture_1001
picture_1002
picture_1003
```

这样不同空间的图片可以落到不同表里。

一句话理解：

```text
分表就是把一张越来越大的表，按某个规则拆成多张更小的表。
```

---

## 2. 为什么云图库适合按 spaceId 分片

云图库项目里，图片可以属于不同空间：

```text
公共图库
私有空间
团队空间
```

`picture` 表里有一个关键字段：

```text
spaceId
```

它表示：

```text
这张图片属于哪个空间
```

如果 `spaceId` 为空，通常表示公共图库图片。

如果 `spaceId = 1001`，表示图片属于空间 1001。

很多业务查询天然都会带空间维度：

```text
查询某个空间下的图片
统计某个空间的图片数量
分析某个空间的图片大小
编辑某个空间里的图片
删除某个空间里的图片
```

所以 `spaceId` 是一个很自然的分片键。

按 `spaceId` 分片后，可以形成这样的效果：

```text
空间 1001 的图片 -> picture_1001
空间 1002 的图片 -> picture_1002
空间 1003 的图片 -> picture_1003
公共图库图片     -> picture
```

这样查询某个空间图片时，就可以只查对应分表，而不是扫整张 `picture` 大表。

---

## 3. ShardingSphere 是什么

ShardingSphere 是 Apache 的分库分表中间件。

它可以理解成：

```text
应用和数据库之间的一层 SQL 路由代理
```

应用层仍然写逻辑 SQL：

```sql
select * from picture where spaceId = 1001;
```

ShardingSphere 根据分片规则，把它路由到真实表：

```sql
select * from picture_1001 where spaceId = 1001;
```

也就是说，业务代码不用到处手写：

```java
if (spaceId == 1001) {
    查 picture_1001
} else if (spaceId == 1002) {
    查 picture_1002
}
```

而是统一交给 ShardingSphere 处理。

一句话理解：

```text
业务代码操作逻辑表，ShardingSphere 根据分片规则决定真正访问哪张物理表。
```

---

## 4. 项目里怎么引入 ShardingSphere

项目在 `pom.xml` 中引入了 ShardingSphere JDBC Starter：

```xml
<dependency>
    <groupId>org.apache.shardingsphere</groupId>
    <artifactId>shardingsphere-jdbc-core-spring-boot-starter</artifactId>
    <version>5.2.0</version>
</dependency>
```

这说明项目采用的是：

```text
ShardingSphere-JDBC
```

它和独立代理模式不同。

ShardingSphere-JDBC 是嵌入到 Java 应用里的：

```text
Spring Boot 应用
-> ShardingSphere-JDBC 数据源
-> MySQL
```

业务代码仍然通过 MyBatis-Plus 查询数据库。

但底层数据源已经变成 ShardingSphere 包装过的数据源。

---

## 5. application.yml 里配置了什么

项目在 `application.yml` 里配置了：

```yaml
spring:
  shardingsphere:
    datasource:
      names: yu_picture
      yu_picture:
        type: com.zaxxer.hikari.HikariDataSource
        driver-class-name: com.mysql.cj.jdbc.Driver
        url: jdbc:mysql://localhost:3306/yu_picture
        username: root
        password: 123456
    rules:
      sharding:
        tables:
          picture:
            actual-data-nodes: yu_picture.picture
            table-strategy:
              standard:
                sharding-column: spaceId
                sharding-algorithm-name: picture_sharding_algorithm
        sharding-algorithms:
          picture_sharding_algorithm:
            type: CLASS_BASED
            props:
              strategy: standard
              algorithmClassName: com.yupi.yupicture.shared.sharding.PictureShardingAlgorithm
    props:
      sql-show: true
```

这段配置可以拆成 4 层理解。

第一层：配置真实数据源。

```text
数据库名：yu_picture
连接池：HikariDataSource
数据库：MySQL
```

第二层：声明逻辑表。

```text
逻辑表：picture
```

第三层：声明分片键。

```text
sharding-column: spaceId
```

意思是：

```text
根据 picture 表里的 spaceId 字段决定路由到哪张表
```

第四层：声明自定义分片算法。

```text
algorithmClassName: com.yupi.yupicture.shared.sharding.PictureShardingAlgorithm
```

意思是：

```text
真正怎么从 spaceId 算出表名，由 PictureShardingAlgorithm 决定
```

---

## 6. 逻辑表和真实表是什么

这是理解 ShardingSphere 的关键。

逻辑表是业务代码看到的表：

```text
picture
```

真实表是数据库中实际存在的表：

```text
picture
picture_1001
picture_1002
picture_1003
```

业务代码一般仍然写：

```sql
select * from picture where spaceId = 1001;
```

ShardingSphere 看到 `spaceId = 1001` 后，根据算法把逻辑表换成真实表：

```sql
select * from picture_1001 where spaceId = 1001;
```

所以可以这样记：

```text
逻辑表给代码用
真实表给数据库存数据
ShardingSphere 负责把逻辑表路由到真实表
```

---

## 7. 分片键为什么是 spaceId

分片键就是：

```text
用哪个字段决定数据去哪个分片
```

项目选择的是：

```text
spaceId
```

原因是图片资源天然归属于空间。

如果按 `id` 分片：

```text
图片 1 到 picture_1
图片 2 到 picture_2
```

这不利于查询某个空间的图片。

如果按 `userId` 分片：

```text
同一个团队空间里不同成员上传的图片可能散落到不同表
```

也不适合团队空间场景。

按 `spaceId` 分片更符合业务访问模式：

```text
一个空间里的图片放在一起
查空间图片时只查一张空间分表
空间维度的数据隔离更清楚
```

这也是面试里很重要的一点：

```text
分片键不是随便选的，要和高频查询条件、数据归属边界、业务隔离维度一致。
```

---

## 8. PictureShardingAlgorithm 做了什么

自定义分片算法在：

```text
PictureShardingAlgorithm
```

它实现了：

```java
StandardShardingAlgorithm<Long>
```

核心方法是：

```java
public String doSharding(Collection<String> availableTargetNames, PreciseShardingValue<Long> preciseShardingValue)
```

这里的几个参数可以这样理解：

```text
availableTargetNames：当前 ShardingSphere 知道的可用真实表名
preciseShardingValue：本次 SQL 里的精确分片值，比如 spaceId = 1001
```

项目算法逻辑是：

```java
Long spaceId = preciseShardingValue.getValue();
String logicTableName = preciseShardingValue.getLogicTableName();

if (spaceId == null) {
    return logicTableName;
}

String realTableName = "picture_" + spaceId;
if (availableTargetNames.contains(realTableName)) {
    return realTableName;
} else {
    return logicTableName;
}
```

翻译成中文就是：

```text
1. 取出当前 SQL 里的 spaceId
2. 如果 spaceId 为空，说明可能是公共图库，走 picture 主表
3. 如果 spaceId 不为空，拼出 picture_{spaceId}
4. 如果 ShardingSphere 当前可用表里有 picture_{spaceId}，就路由到这张分表
5. 如果没有这张分表，就回退到 picture 主表
```

比如：

```text
spaceId = 1001
目标表名 = picture_1001
```

如果 `picture_1001` 存在并且在 `actual-data-nodes` 里，就查它。

如果不存在，就回退查 `picture`。

---

## 9. 为什么 spaceId 为 null 要走 picture 主表

项目里 `picture.spaceId` 是后来新增的字段：

```sql
ALTER TABLE picture
    ADD COLUMN spaceId bigint null comment '空间 id（为空表示公共空间）';
```

也就是说：

```text
spaceId 为空，通常表示公共图库图片
```

公共图库不是某个团队空间专属。

所以算法里写了：

```java
if (spaceId == null) {
    return logicTableName;
}
```

`logicTableName` 就是：

```text
picture
```

这表示：

```text
公共图库图片仍然放在 picture 主表里
团队空间图片才考虑放到 picture_{spaceId} 分表里
```

---

## 10. 为什么找不到分表时要回退到 picture

算法里还有一段：

```java
if (availableTargetNames.contains(realTableName)) {
    return realTableName;
} else {
    return logicTableName;
}
```

这表示：

```text
如果目标分表不存在，回退到 picture 主表
```

这个设计适合当前项目的“雏形阶段”。

因为不是所有空间都一定已经创建分表。

比如：

```text
普通私有空间 -> 可能仍然放 picture 主表
普通团队空间 -> 可能仍然放 picture 主表
旗舰版团队空间 -> 才计划创建 picture_{spaceId}
```

所以回退逻辑可以保证：

```text
即使分表没建出来，查询不会直接失败
```

但它也有一个风险：

```text
如果本来应该查分表，却因为配置没更新而回退到主表，可能查不到预期数据
```

所以生产级要保证：

```text
分表创建
actual-data-nodes 更新
分片算法
数据写入
```

这几步是一致的。

---

## 11. 当前配置为什么说是“分片雏形”

现在 `application.yml` 里写的是：

```yaml
actual-data-nodes: yu_picture.picture
```

这表示 ShardingSphere 当前静态配置里只知道一张真实表：

```text
picture
```

但算法会尝试路由到：

```text
picture_{spaceId}
```

比如：

```text
picture_1001
```

问题是：

```text
如果 actual-data-nodes 里没有 picture_1001
availableTargetNames 就不会包含 picture_1001
算法最后会回退到 picture
```

所以当前静态配置更像是：

```text
ShardingSphere 已经接入
分片键已经选好
自定义算法已经写好
但动态分表节点还没有正式启用
```

这就是“按 spaceId 分片的算法雏形”。

面试时不能夸大成：

```text
已经完整生产级落地动态分表
```

更准确的说法是：

```text
项目已经引入 ShardingSphere-JDBC，并基于 spaceId 预留了图片表按空间分表的路由算法；当前为了部署方便，动态建表和动态刷新 actual-data-nodes 暂时注释，属于可扩展的分表雏形。
```

---

## 12. DynamicShardingManager 是干什么的

`DynamicShardingManager` 是动态分表管理器。

它的目标是解决一个问题：

```text
新空间创建后，如何动态创建 picture_{spaceId} 分表，并让 ShardingSphere 知道这张新表
```

它主要做两件事：

```text
1. 动态创建真实分表
2. 动态更新 ShardingSphere 的 actual-data-nodes
```

也就是说，它是让“分片算法雏形”走向“动态分表落地”的关键类。

不过当前代码里它的 `@Component` 被注释了：

```java
//@Component
public class DynamicShardingManager {
    ...
}
```

空间创建服务里的注入也被注释了：

```java
// 为了方便部署，注释掉分表
//    @Resource
//    @Lazy
//    private DynamicShardingManager dynamicShardingManager;
```

创建空间后的调用也被注释了：

```java
// 创建分表（仅对团队空间生效）为方便部署，暂时不使用
// dynamicShardingManager.createSpacePictureTable(space);
```

这说明：

```text
动态分表管理能力已经写了雏形，但当前运行时没有启用
```

---

## 13. 动态分表准备怎么创建表

`DynamicShardingManager#createSpacePictureTable` 里有这样的逻辑：

```java
if (space.getSpaceType() == SpaceTypeEnum.TEAM.getValue()
        && space.getSpaceLevel() == SpaceLevelEnum.FLAGSHIP.getValue()) {
    Long spaceId = space.getId();
    String tableName = LOGIC_TABLE_NAME + "_" + spaceId;
    String createTableSql = "CREATE TABLE " + tableName + " LIKE " + LOGIC_TABLE_NAME;
    SqlRunner.db().update(createTableSql);
    updateShardingTableNodes();
}
```

翻译成中文：

```text
1. 只给团队空间创建分表
2. 且只给旗舰版团队空间创建分表
3. 分表名是 picture_{spaceId}
4. 使用 CREATE TABLE picture_{spaceId} LIKE picture 复制主表结构
5. 创建成功后刷新 ShardingSphere 的 actual-data-nodes
```

为什么只给旗舰版团队空间创建分表？

因为不是所有空间都值得单独分表。

普通空间数据量可能不大。

旗舰版团队空间更可能有大量图片和多人协作。

所以设计上可以是：

```text
普通空间 -> 继续使用 picture 主表
旗舰版团队空间 -> 独立 picture_{spaceId} 分表
```

这属于按业务价值做差异化存储。

---

## 14. 动态 actual-data-nodes 是什么

ShardingSphere 不能只靠数据库里真的有表。

它还要在规则里知道：

```text
哪些真实表属于 picture 这张逻辑表
```

这就是 `actual-data-nodes`。

比如完整启用后，可能是：

```text
yu_picture.picture,yu_picture.picture_1001,yu_picture.picture_1002
```

如果 `picture_1001` 在数据库里存在，但 ShardingSphere 的 `actual-data-nodes` 没有它，分片算法里的：

```java
availableTargetNames.contains("picture_1001")
```

就可能判断为 false。

然后就会回退到：

```text
picture
```

所以动态分表不只是创建表，还要刷新规则。

`DynamicShardingManager#updateShardingTableNodes` 的目标就是：

```text
把当前所有 picture 和 picture_{spaceId} 表重新拼成 actual-data-nodes
再通知 ShardingSphere 更新规则并 reload
```

---

## 15. DynamicShardingManager 怎么刷新规则

它大致流程是：

```text
1. 查询需要分表的空间 id
2. 拼出所有真实表名
3. 拼成新的 actual-data-nodes 字符串
4. 获取 ShardingSphere 的 ContextManager
5. 找到当前 ShardingRule
6. 替换 picture 逻辑表的 actual-data-nodes
7. 调用 alterRuleConfiguration 更新规则
8. 调用 reloadDatabase 重新加载数据库元数据
```

核心代码包括：

```java
ContextManager contextManager = getContextManager();
ShardingSphereRuleMetaData ruleMetaData = contextManager.getMetaDataContexts()
        .getMetaData()
        .getDatabases()
        .get(DATABASE_NAME)
        .getRuleMetaData();
```

以及：

```java
contextManager.alterRuleConfiguration(DATABASE_NAME, Collections.singleton(ruleConfig));
contextManager.reloadDatabase(DATABASE_NAME);
```

这说明作者考虑到了一个现实问题：

```text
分表不是项目启动前就全部存在，新空间创建后可能要动态扩展分片规则
```

不过当前这个类没有启用，所以它仍然是预留能力。

---

## 16. 空间创建流程里预留了什么

在 `SpaceApplicationServiceImpl#addSpace` 中，创建空间时有预留代码：

```java
// 创建分表（仅对团队空间生效）为方便部署，暂时不使用
// dynamicShardingManager.createSpacePictureTable(space);
```

它的位置在：

```text
空间保存成功
团队空间成员关系创建成功
返回空间 id 之前
```

这说明设计意图是：

```text
用户创建一个符合条件的团队空间
-> 系统保存 space 记录
-> 系统创建空间管理员成员记录
-> 系统为这个空间创建 picture_{spaceId} 分表
-> 刷新 ShardingSphere 路由规则
```

但当前为了方便部署，这一段没有开启。

面试时可以说：

```text
空间创建链路中已经预留了动态分表调用点，只是当前版本为了降低本地部署复杂度暂时注释。
```

---

## 17. SQL 表结构和分片字段

建表 SQL 里，`picture` 表后来新增了：

```sql
ALTER TABLE picture
    ADD COLUMN spaceId bigint null comment '空间 id（为空表示公共空间）';
```

同时创建了索引：

```sql
CREATE INDEX idx_spaceId ON picture (spaceId);
```

这说明即使不分表，项目也已经针对空间维度查询做了索引优化。

分表以后，`spaceId` 仍然重要，因为它是：

```text
ShardingSphere 的分片键
业务查询空间图片的高频条件
权限体系定位空间资源的重要字段
```

可以这样理解：

```text
spaceId 既是业务归属字段，也是查询优化字段，也是未来分表路由字段。
```

---

## 18. 一条查询会怎么被路由

假设业务代码查询空间 1001 下的图片：

```sql
select * from picture where spaceId = 1001;
```

理想分片流程是：

```text
1. MyBatis-Plus 发出查询 picture 逻辑表的 SQL
2. ShardingSphere 拦截 SQL
3. 发现分片键 spaceId = 1001
4. 调用 PictureShardingAlgorithm#doSharding
5. 算出目标表 picture_1001
6. 判断 picture_1001 是否在 availableTargetNames 中
7. 如果存在，路由到 picture_1001
8. 如果不存在，回退到 picture 主表
```

所以完整落地后的效果应该是：

```text
查询空间 1001 -> picture_1001
查询空间 1002 -> picture_1002
查询公共图库 -> picture
```

当前版本因为动态节点没有启用，大多数情况仍然会回到 `picture` 主表。

---

## 19. 一条写入会怎么被路由

假设上传图片时带了：

```text
spaceId = 1001
```

理想情况下，插入 SQL 是：

```sql
insert into picture (...) values (..., 1001, ...);
```

ShardingSphere 会根据 `spaceId = 1001` 路由到：

```text
picture_1001
```

如果是公共图库图片，`spaceId = null`，则写到：

```text
picture
```

但是要注意：

```text
写入分表的前提是目标分表已经创建，并且 actual-data-nodes 已经包含这张表。
```

否则算法会回退到主表。

这也是为什么动态建表和动态刷新节点必须配套。

---

## 20. 范围查询目前没有完整实现

`PictureShardingAlgorithm` 里还有一个方法：

```java
public Collection<String> doSharding(Collection<String> collection, RangeShardingValue<Long> rangeShardingValue) {
    return new ArrayList<>();
}
```

这是处理范围分片的。

比如：

```sql
select * from picture where spaceId between 1000 and 2000;
```

或者：

```sql
select * from picture where spaceId > 1000;
```

当前代码直接返回空集合。

这说明：

```text
当前算法主要支持 spaceId = xxx 这种精确查询
范围查询还没有完整实现
```

这也是“雏形”的一个证据。

面试时可以主动说：

```text
当前业务主要是按某个空间查询图片，所以优先实现了精确分片；范围查询和全路由策略后续还需要补齐。
```

---

## 21. 为什么这个分片设计不是按数量取模

很多分表案例会这样做：

```text
picture_0
picture_1
picture_2
picture_3
spaceId % 4 决定落哪张表
```

这种叫取模分片。

项目当前算法不是这样，而是：

```text
picture_{spaceId}
```

它更像是按空间独立分表。

优点是：

```text
1. 空间数据隔离非常清楚
2. 查询某个空间时能精准命中一张表
3. 大客户或旗舰团队空间可以单独扩容和迁移
4. 删除或归档某个空间数据更容易
```

缺点是：

```text
1. 空间多时表数量可能很多
2. 需要动态建表和动态刷新规则
3. 跨空间统计会变复杂
4. 表数量过多会增加数据库元数据管理成本
```

所以它适合：

```text
只给大空间、旗舰版团队空间单独建表
不是给所有普通空间都建表
```

这也是 `DynamicShardingManager` 里只给旗舰版团队空间建表的原因。

---

## 22. 和空间权限体系是什么关系

Sa-Token 空间 RBAC 里也大量使用 `spaceId`。

权限体系关心：

```text
当前用户在这个 spaceId 对应空间里是什么角色
```

分片体系关心：

```text
这个 spaceId 对应的图片应该查哪张表
```

两者都围绕空间维度展开。

但职责不同：

```text
RBAC 负责判断能不能操作
ShardingSphere 负责决定数据去哪张表
```

可以这样记：

```text
权限体系用 spaceId 做访问控制
分片体系用 spaceId 做数据路由
```

这两个点放在一起讲，能体现项目是围绕“空间”做了权限隔离和数据隔离设计。

---

## 23. 和团队空间业务是什么关系

团队空间通常比私有空间更容易产生大量图片。

因为团队空间有：

```text
多个成员上传
多人协作编辑
空间分析统计
成员权限管理
```

所以团队空间是图片数据增长的重点。

如果某个团队空间是旗舰版，说明它可能是高价值、高容量空间。

这时给它单独建表更合理：

```text
旗舰团队空间 A -> picture_A
旗舰团队空间 B -> picture_B
普通空间和公共图库 -> picture
```

这种设计是面向 SaaS 多租户场景的：

```text
大租户独立分表
小租户共享主表
```

云图库里的“空间”就可以理解成一种轻量租户。

---

## 24. 当前实现的完整链路设想

如果把注释掉的动态分表能力打开，完整链路应该是：

```text
1. 用户创建团队空间
2. 后端判断空间类型和空间级别
3. 如果是旗舰版团队空间，调用 DynamicShardingManager
4. 执行 CREATE TABLE picture_{spaceId} LIKE picture
5. 查询所有需要分表的团队空间
6. 拼接新的 actual-data-nodes
7. 更新 ShardingSphere 分片规则
8. 后续上传该空间图片时，按 spaceId 路由到 picture_{spaceId}
9. 查询该空间图片时，也按 spaceId 路由到 picture_{spaceId}
```

可以浓缩成：

```text
创建空间时建表，运行时根据 spaceId 路由，查询和写入都走对应空间分表。
```

---

## 25. 为什么当前为了部署方便注释掉

代码里写了：

```java
// 为了方便部署，注释掉分表
```

这其实很现实。

动态分表会增加本地部署和测试成本：

```text
要确保 ShardingSphere 配置正确
要确保数据库用户有 CREATE TABLE 权限
要确保动态规则刷新兼容当前版本
要处理分表不存在、重复建表、建表失败等异常
要处理本地环境和线上环境差异
```

对秋招项目来说，暂时注释掉不代表设计没价值。

更准确的定位是：

```text
项目已经预留了分片架构能力，但当前主流程为了稳定部署仍走单表。
```

面试时这样讲会更稳。

---

## 26. 小白最容易混淆的几个点

### 26.1 ShardingSphere 不是数据库

ShardingSphere 不是 MySQL。

它是应用和数据库之间的分片中间件。

真正存数据的还是 MySQL 表。

### 26.2 逻辑表不等于真实表

逻辑表：

```text
picture
```

真实表：

```text
picture
picture_1001
picture_1002
```

业务代码操作逻辑表，ShardingSphere 决定真实表。

### 26.3 只写算法不代表分表完全生效

完整分表要同时满足：

```text
1. 引入 ShardingSphere 依赖
2. 配置 ShardingSphere 数据源
3. 配置逻辑表和 actual-data-nodes
4. 配置分片键
5. 实现分片算法
6. 数据库真实分表存在
7. actual-data-nodes 包含真实分表
8. SQL 中带有分片键
```

当前项目已经完成了前几步，但动态分表节点还没有正式启用。

### 26.4 spaceId 是分片键，不是主键

`id` 是图片主键。

`spaceId` 是图片所属空间。

按 `spaceId` 分片，是为了空间维度的数据隔离和查询优化。

### 26.5 分表不能代替索引

分表减少的是单表数据量。

索引优化的是单表内部查询效率。

项目里仍然给 `spaceId` 建了索引：

```sql
CREATE INDEX idx_spaceId ON picture (spaceId);
```

这说明分表和索引是互补关系。

---

## 27. 这套设计的优点

这套实现的优点可以这样讲：

```text
1. 引入 ShardingSphere-JDBC，对业务代码侵入较小，MyBatis-Plus 仍然操作 picture 逻辑表。
2. 选择 spaceId 作为分片键，符合云图库按空间查询、统计和隔离的业务模型。
3. 自定义 PictureShardingAlgorithm，可以按 picture_{spaceId} 精准路由到空间分表。
4. spaceId 为空时回退 picture 主表，兼容公共图库图片。
5. 目标分表不存在时回退 picture 主表，便于分片架构逐步演进。
6. DynamicShardingManager 预留了动态建表和刷新 actual-data-nodes 的能力。
7. 只计划给旗舰版团队空间建分表，避免普通空间过度拆表导致表数量膨胀。
8. 与空间 RBAC、团队空间、空间分析等业务天然围绕 spaceId 统一建模。
```

---

## 28. 当前实现的边界和可优化点

### 28.1 动态分表当前未启用

`DynamicShardingManager` 的 `@Component` 被注释。

空间创建时调用 `createSpacePictureTable` 的代码也被注释。

所以当前运行时大概率还是单表为主。

优化方向：

```text
在生产环境配置开关中启用动态分表
创建空间成功后自动建表
建表成功后刷新 actual-data-nodes
增加失败回滚和告警
```

### 28.2 actual-data-nodes 当前只配置了 picture

当前配置是：

```yaml
actual-data-nodes: yu_picture.picture
```

如果要让 `picture_1001` 生效，需要动态或静态加入：

```text
yu_picture.picture_1001
```

否则算法会回退到主表。

### 28.3 范围分片没有实现

`RangeShardingValue` 当前返回空集合。

如果后续有范围查询，需要补全：

```text
范围查询路由策略
全表路由策略
或禁止不带明确 spaceId 的分片查询
```

### 28.4 表数量可能膨胀

如果每个空间都建一张表，空间多了以后会出现：

```text
MySQL 表太多
元数据管理变复杂
备份恢复成本变高
跨空间统计变慢
```

所以更合理的是：

```text
只给大空间或旗舰版团队空间独立分表
普通空间仍然共享主表
```

### 28.5 数据迁移策略还需要补齐

如果一个普通空间升级成旗舰版团队空间，可能需要：

```text
创建 picture_{spaceId}
把原来 picture 主表中该 spaceId 的历史图片迁移过去
校验迁移数量
切换路由
清理旧数据
```

当前代码只看到建表和刷新规则，历史数据迁移还没有完整实现。

### 28.6 分布式场景要考虑规则同步

如果后端部署多台机器，某一台机器更新了 ShardingSphere 规则，其他机器也要同步。

优化方向：

```text
使用统一配置中心
使用 ShardingSphere 支持的治理模式
通过服务重启或广播机制刷新多节点规则
```

---

## 29. 面试怎么讲这套功能

可以这样讲第一层：

```text
我在项目中引入了 ShardingSphere-JDBC，围绕图片表 picture 设计了按 spaceId 分片的架构雏形。因为云图库的图片天然归属于空间，大部分查询和统计都围绕空间展开，所以选择 spaceId 作为分片键，让团队空间图片未来可以路由到 picture_{spaceId} 这样的独立分表中。
```

再讲第二层：

```text
项目在 application.yml 中配置了 ShardingSphere 数据源、picture 逻辑表、spaceId 分片键以及 CLASS_BASED 自定义分片算法。PictureShardingAlgorithm 会根据 SQL 中的 spaceId 拼出 picture_{spaceId} 作为目标表；如果 spaceId 为空，则认为是公共图库图片，回退到 picture 主表；如果目标分表当前不在 availableTargetNames 中，也会回退到 picture 主表，保证未完全启用分表时系统仍可运行。
```

再讲第三层：

```text
为了支持动态分表，项目还预留了 DynamicShardingManager。它的设计思路是在创建旗舰版团队空间时，执行 CREATE TABLE picture_{spaceId} LIKE picture 动态创建空间图片分表，然后通过 ShardingSphere ContextManager 更新 actual-data-nodes 并 reload 规则。不过当前为了方便部署，这个组件和空间创建链路中的调用暂时注释掉了，所以当前属于分片能力雏形，而不是完整生产级动态分表落地。
```

最后讲优化：

```text
后续如果要生产化，需要补齐动态分表开关、历史数据迁移、范围查询路由、分布式规则同步、建表失败回滚和监控告警。同时要控制表数量，只给数据量大的旗舰版团队空间独立分表，避免过度拆表。
```

---

## 30. 最终一句话吃透

这套 ShardingSphere 分片体系本质是：

```text
用 ShardingSphere-JDBC 接管数据源，让业务代码仍然操作 picture 逻辑表，再用 spaceId 作为分片键，通过自定义 PictureShardingAlgorithm 把空间图片路由到 picture_{spaceId} 分表；同时预留 DynamicShardingManager 在创建旗舰版团队空间时动态建表并刷新 actual-data-nodes，只是当前为了部署方便暂未启用动态分表。
```

再浓缩成面试表达：

```text
我在云图库项目中基于 ShardingSphere-JDBC 设计了按 spaceId 对图片表进行分片的架构雏形。项目将 picture 作为逻辑表，在配置中指定 spaceId 为分片键，并通过自定义 PictureShardingAlgorithm 将空间图片路由到 picture_{spaceId}；公共图库或未创建分表的空间会回退到 picture 主表。为了支持动态扩展，项目还预留了 DynamicShardingManager，可以在创建旗舰版团队空间时执行 CREATE TABLE picture_{spaceId} LIKE picture，并动态刷新 ShardingSphere 的 actual-data-nodes。当前该能力为了降低部署复杂度暂时注释，后续可补齐动态启用、数据迁移和多节点规则同步，演进为生产级分表方案。
```
