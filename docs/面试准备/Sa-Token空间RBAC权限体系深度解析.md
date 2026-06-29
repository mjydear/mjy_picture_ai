# Sa-Token 空间 RBAC 权限体系深度解析

本文用于秋招复习，专门拆解云图库项目中 **Sa-Token + 自定义空间权限实现团队空间 RBAC 权限体系** 的完整实现逻辑。

相关核心代码：

- `mjy-picture-backend-ddd/src/main/java/com/yupi/yupicture/shared/auth/annotation/SaSpaceCheckPermission.java`
- `mjy-picture-backend-ddd/src/main/java/com/yupi/yupicture/shared/auth/StpKit.java`
- `mjy-picture-backend-ddd/src/main/java/com/yupi/yupicture/shared/auth/StpInterfaceImpl.java`
- `mjy-picture-backend-ddd/src/main/java/com/yupi/yupicture/shared/auth/SpaceUserAuthManager.java`
- `mjy-picture-backend-ddd/src/main/resources/biz/spaceUserAuthConfig.json`
- `mjy-picture-backend-ddd/src/main/java/com/yupi/yupicture/domain/space/entity/SpaceUser.java`
- `mjy-picture-backend-ddd/src/main/java/com/yupi/yupicture/interfaces/controller/PictureController.java`
- `mjy-picture-backend-ddd/src/main/java/com/yupi/yupicture/interfaces/controller/SpaceUserController.java`

---

## 1. 先搞懂 RBAC 是什么

RBAC 全称是 **Role-Based Access Control**，中文叫“基于角色的访问控制”。

它的思想非常简单：

```text
用户不直接拥有权限
用户拥有角色
角色拥有权限
所以用户通过角色间接获得权限
```

比如团队空间里有 3 个角色：

| 角色 | 中文 | 能力 |
| --- | --- | --- |
| `viewer` | 浏览者 | 只能看图片 |
| `editor` | 编辑者 | 能看、上传、编辑、删除图片 |
| `admin` | 管理员 | 能看、上传、编辑、删除图片，还能管理成员 |

假设一个团队空间叫“产品素材库”：

```text
张三：admin
李四：editor
王五：viewer
```

那么：

```text
张三可以添加成员、删除成员、上传图片、删除图片
李四可以上传和编辑图片，但不能管理成员
王五只能看，不能改
```

这就是团队空间 RBAC。

---

## 2. 这个项目为什么需要空间级 RBAC

普通项目通常只有一种权限：

```text
普通用户 user
管理员 admin
```

这种叫“系统级权限”。

但云图库项目更复杂，因为它有“空间”：

```text
公共图库
私有空间
团队空间
```

同一个用户在不同空间里的权限可能不一样：

```text
张三在 A 空间是 admin
张三在 B 空间是 viewer
张三在 C 空间可能不是成员
```

所以不能只看 `userRole = admin/user`，还要看：

```text
当前访问的是哪个空间？
当前用户是不是这个空间的成员？
当前用户在这个空间里是什么角色？
这个角色有没有当前接口需要的权限？
```

这就是项目引入空间 RBAC 的原因。

---

## 3. 数据库怎么存权限关系

核心表是 `space_user`，实体类是 `SpaceUser`。

表结构核心字段：

```sql
spaceId    空间 id
userId     用户 id
spaceRole  空间角色：viewer/editor/admin
```

也就是说，`space_user` 表不是存“权限”，而是存：

```text
某个用户在某个空间里是什么角色
```

例如：

| id | spaceId | userId | spaceRole |
| --- | ---: | ---: | --- |
| 1 | 1001 | 10 | admin |
| 2 | 1001 | 11 | editor |
| 3 | 1001 | 12 | viewer |

含义是：

```text
用户 10 是空间 1001 的管理员
用户 11 是空间 1001 的编辑者
用户 12 是空间 1001 的浏览者
```

表里还有一个唯一索引：

```sql
UNIQUE KEY uk_spaceId_userId (spaceId, userId)
```

意思是：

```text
同一个用户在同一个空间里只能有一个角色
```

否则就会出现一个用户既是 viewer 又是 admin 的混乱情况。

---

## 4. 权限码是什么

项目把所有空间权限定义成字符串常量，位置在 `SpaceUserPermissionConstant`。

目前有 5 个权限码：

| 权限码 | 含义 |
| --- | --- |
| `spaceUser:manage` | 管理空间成员 |
| `picture:view` | 查看图片 |
| `picture:upload` | 上传图片 |
| `picture:edit` | 编辑图片 |
| `picture:delete` | 删除图片 |

你可以把权限码理解成“接口通行证”。

比如：

```text
上传图片接口需要 picture:upload
删除图片接口需要 picture:delete
管理成员接口需要 spaceUser:manage
```

---

## 5. 角色和权限怎么绑定

角色和权限的对应关系写在 `spaceUserAuthConfig.json`。

核心配置是：

```json
{
  "roles": [
    {
      "key": "viewer",
      "permissions": ["picture:view"]
    },
    {
      "key": "editor",
      "permissions": [
        "picture:view",
        "picture:upload",
        "picture:edit",
        "picture:delete"
      ]
    },
    {
      "key": "admin",
      "permissions": [
        "spaceUser:manage",
        "picture:view",
        "picture:upload",
        "picture:edit",
        "picture:delete"
      ]
    }
  ]
}
```

所以系统判断权限时，不是写死：

```java
if (role.equals("admin")) {
    // 放行
}
```

而是：

```text
先查出用户在当前空间里的角色
再从 JSON 配置里找到这个角色拥有的权限列表
再判断权限列表里有没有当前接口需要的权限
```

这就是 RBAC 的标准做法。

---

## 6. Sa-Token 在这里负责什么

Sa-Token 是一个 Java 权限认证框架。

可以先粗略理解为：

```text
Sa-Token 帮你做登录态管理、权限校验、注解拦截
```

比如接口上写：

```java
@SaSpaceCheckPermission(value = SpaceUserPermissionConstant.PICTURE_UPLOAD)
```

意思是：

```text
访问这个接口的人，必须拥有 picture:upload 权限
```

如果有权限，接口继续执行。

如果没权限，Sa-Token 直接拦住请求，不让它进入方法内部。

项目里上传图片接口就是这样写的：

```java
@PostMapping("/upload")
@SaSpaceCheckPermission(value = SpaceUserPermissionConstant.PICTURE_UPLOAD)
public BaseResponse<PictureVO> uploadPicture(...) {
    ...
}
```

删除图片接口：

```java
@PostMapping("/delete")
@SaSpaceCheckPermission(value = SpaceUserPermissionConstant.PICTURE_DELETE)
public BaseResponse<Boolean> deletePicture(...) {
    ...
}
```

管理空间成员接口：

```java
@PostMapping("/add")
@SaSpaceCheckPermission(value = SpaceUserPermissionConstant.SPACE_USER_MANAGE)
public BaseResponse<Long> addSpaceUser(...) {
    ...
}
```

也就是说：

```text
接口上标注需要什么权限
Sa-Token 负责拦截请求
自定义逻辑负责告诉 Sa-Token 当前用户到底有哪些权限
```

---

## 7. 为什么要自定义 `@SaSpaceCheckPermission`

项目没有直接到处写 Sa-Token 原生的 `@SaCheckPermission`，而是封装了自己的注解 `@SaSpaceCheckPermission`。

它本质上是对 Sa-Token 注解的二次封装：

```java
@SaCheckPermission(type = StpKit.SPACE_TYPE)
public @interface SaSpaceCheckPermission {
    String[] value() default {};
}
```

重点是：

```java
type = StpKit.SPACE_TYPE
```

`StpKit.SPACE_TYPE` 的值是：

```java
public static final String SPACE_TYPE = "space";
```

这表示：

```text
这不是普通系统权限校验
这是 space 这套空间权限体系的校验
```

为什么要这样做？

因为项目里可能有两套权限：

```text
系统权限：管理员 user/admin
空间权限：viewer/editor/admin
```

系统管理员权限用普通 `@AuthCheck`。

团队空间权限用 `@SaSpaceCheckPermission`。

这样职责更清楚。

---

## 8. `StpKit` 是干什么的

`StpKit` 定义了一套叫 `space` 的 Sa-Token 登录体系：

```java
public static final String SPACE_TYPE = "space";

public static final StpLogic SPACE = new StpLogic(SPACE_TYPE);
```

可以理解为：

```text
系统给“空间权限”单独开了一套 Sa-Token 权限逻辑
```

登录时，项目会把当前用户登录到这套 `SPACE` 体系里。

在 `UserDomainServiceImpl` 的登录逻辑里：

```java
StpKit.SPACE.login(user.getId());
StpKit.SPACE.getSession().set(UserConstant.USER_LOGIN_STATE, user);
```

意思是：

```text
用户登录成功后
把 userId 注册到 Sa-Token 的 space 体系
并把用户对象放进 space session
```

后面做空间权限判断时，就能知道当前请求是谁发起的。

---

## 9. 最核心：Sa-Token 怎么知道用户有哪些权限

关键在 `StpInterfaceImpl`。

这个类实现了 Sa-Token 的接口：

```java
public class StpInterfaceImpl implements StpInterface
```

里面最重要的方法是：

```java
public List<String> getPermissionList(Object loginId, String loginType)
```

这句话很关键：

```text
Sa-Token 要检查权限时，会自动调用 getPermissionList
这个方法返回当前用户拥有的权限列表
```

比如用户访问上传接口：

```java
@SaSpaceCheckPermission(value = "picture:upload")
```

Sa-Token 会问项目：

```text
当前用户有哪些权限？
```

项目通过 `getPermissionList` 返回：

```text
["picture:view", "picture:upload", "picture:edit", "picture:delete"]
```

Sa-Token 一看里面有 `picture:upload`，就放行。

如果返回：

```text
["picture:view"]
```

那就说明用户只是 viewer，没有上传权限，接口会被拦截。

---

## 10. 权限判断完整流程

以“团队空间成员上传图片”为例。

用户请求：

```http
POST /picture/upload
```

请求里带了：

```text
spaceId = 1001
```

接口上有注解：

```java
@SaSpaceCheckPermission(value = "picture:upload")
```

完整流程是：

```text
1. 用户访问上传图片接口
2. Sa-Token 看到接口需要 picture:upload 权限
3. Sa-Token 调用 StpInterfaceImpl#getPermissionList
4. getPermissionList 从请求里解析出 spaceId = 1001
5. 根据当前登录用户 userId 和 spaceId 查询 space_user 表
6. 查到用户在空间 1001 里的角色，比如 editor
7. 从 spaceUserAuthConfig.json 中查 editor 拥有哪些权限
8. editor 权限列表包含 picture:upload
9. Sa-Token 放行
10. Controller 方法真正执行上传逻辑
```

如果用户是 viewer：

```text
viewer 只有 picture:view
不包含 picture:upload
所以上传接口被拒绝
```

---

## 11. 系统怎么知道当前请求对应哪个空间

这是新手最容易懵的地方。

权限判断必须知道：

```text
当前操作的是哪个空间？
```

但不同接口传参不一样：

```text
上传图片：可能传 spaceId
删除图片：可能只传 pictureId
管理成员：可能传 spaceUserId
空间详情：可能传 id
```

所以项目定义了一个上下文对象 `SpaceUserAuthContext`：

```java
private Long id;
private Long pictureId;
private Long spaceId;
private Long spaceUserId;
private Picture picture;
private Space space;
private SpaceUser spaceUser;
```

它的作用是：

```text
统一保存本次权限校验需要的信息
```

`StpInterfaceImpl` 里有一个方法：

```java
getAuthContextByRequest()
```

它会从当前 HTTP 请求里读参数：

- 如果是 JSON 请求，就读 body。
- 如果是普通请求参数，就读 param。
- 如果请求里有通用字段 `id`，它会根据 URL 判断这个 `id` 到底是什么。

比如：

```text
/api/picture/delete 里的 id -> pictureId
/api/spaceUser/delete 里的 id -> spaceUserId
/api/space/edit 里的 id -> spaceId
```

核心逻辑类似：

```java
String moduleName = ...
switch (moduleName) {
    case "picture":
        authRequest.setPictureId(id);
        break;
    case "spaceUser":
        authRequest.setSpaceUserId(id);
        break;
    case "space":
        authRequest.setSpaceId(id);
        break;
}
```

所以它能把不同接口里的参数统一转成权限判断需要的上下文。

---

## 12. 不同场景下权限怎么判断

### 12.1 没有任何空间上下文

比如公共图库列表查询：

```text
没有 spaceId
没有 pictureId
没有 spaceUserId
```

代码里认为这种情况默认返回管理员权限集合：

```java
if (isAllFieldsNull(authContext)) {
    return ADMIN_PERMISSIONS;
}
```

注意：这个地方更像是为了让无空间上下文的请求能通过注解校验。具体业务接口里还会有额外判断，比如公开图库只展示审核通过的图片。

### 12.2 请求里有 `spaceUserId`

常见于：

```text
编辑空间成员
删除空间成员
查询空间成员
```

流程是：

```text
1. 通过 spaceUserId 查到被操作的成员记录
2. 得到这个成员属于哪个 spaceId
3. 再查当前登录用户在这个 spaceId 里的成员记录
4. 根据当前登录用户的 spaceRole 返回权限
```

为什么不能直接看被操作成员的角色？

因为权限判断的是：

```text
当前操作者有没有权限
```

不是：

```text
被操作的人有没有权限
```

### 12.3 请求里有 `spaceId`

常见于：

```text
上传图片到空间
查看空间详情
空间分析
```

流程是：

```text
1. 查询 space 表，拿到空间信息
2. 判断空间是私有空间还是团队空间
3. 如果是私有空间，只允许空间创建者或系统管理员拥有全部权限
4. 如果是团队空间，查 space_user 表，看当前用户在这个空间是什么角色
5. 根据角色返回权限列表
```

### 12.4 请求里只有 `pictureId`

常见于：

```text
删除图片
编辑图片
查看图片详情
AI 扩图
```

流程是：

```text
1. 先用 pictureId 查 picture 表
2. 从 picture 里拿到 spaceId
3. 如果 spaceId 为空，说明是公共图库图片
4. 如果 spaceId 不为空，再按空间逻辑判断权限
```

公共图库图片的处理是：

```text
如果图片属于当前用户，或者当前用户是系统管理员
-> 给管理员级别空间权限

如果不是自己的图片
-> 只给 picture:view 查看权限
```

### 12.5 团队空间

团队空间是最标准的 RBAC：

```text
spaceId + userId -> 查 space_user -> 得到 spaceRole -> 查 JSON -> 得到权限列表
```

比如：

```text
spaceId = 1001
userId = 11
查 space_user 得到 editor
查 JSON 得到：
[
  "picture:view",
  "picture:upload",
  "picture:edit",
  "picture:delete"
]
```

然后 Sa-Token 判断当前接口需要的权限是否在这个列表里。

---

## 13. 团队空间创建时，admin 角色怎么来的

在 `SpaceApplicationServiceImpl` 里，创建空间时有一段逻辑：

```java
if (SpaceTypeEnum.TEAM.getValue() == space.getSpaceType()) {
    SpaceUser spaceUser = new SpaceUser();
    spaceUser.setSpaceId(space.getId());
    spaceUser.setUserId(userId);
    spaceUser.setSpaceRole(SpaceRoleEnum.ADMIN.getValue());
    spaceUserApplicationService.save(spaceUser);
}
```

意思是：

```text
用户创建团队空间成功后
系统自动往 space_user 表插入一条记录
把创建者设置为这个团队空间的 admin
```

所以团队空间创建者天然拥有：

```text
成员管理
查看图片
上传图片
编辑图片
删除图片
```

这也是为什么创建者能邀请别人加入团队空间。

---

## 14. 添加成员时发生了什么

添加空间成员接口：

```java
@PostMapping("/add")
@SaSpaceCheckPermission(value = SpaceUserPermissionConstant.SPACE_USER_MANAGE)
public BaseResponse<Long> addSpaceUser(...) {
    ...
}
```

它要求当前用户必须有：

```text
spaceUser:manage
```

也就是说，只有空间 admin 能添加成员。

真正新增成员的逻辑在 `SpaceUserApplicationServiceImpl`：

```java
SpaceUser spaceUser = new SpaceUser();
BeanUtils.copyProperties(spaceUserAddRequest, spaceUser);
validSpaceUser(spaceUser, true);
this.save(spaceUser);
```

`validSpaceUser` 会校验：

```text
spaceId 必须存在
userId 必须存在
用户必须存在
空间必须存在
spaceRole 必须是 viewer/editor/admin 之一
```

然后保存到 `space_user` 表。

---

## 15. 几个典型接口怎么被保护

上传图片：

```java
@SaSpaceCheckPermission(value = SpaceUserPermissionConstant.PICTURE_UPLOAD)
```

需要：

```text
picture:upload
```

viewer 不行，editor/admin 可以。

删除图片：

```java
@SaSpaceCheckPermission(value = SpaceUserPermissionConstant.PICTURE_DELETE)
```

需要：

```text
picture:delete
```

viewer 不行，editor/admin 可以。

编辑图片：

```java
@SaSpaceCheckPermission(value = SpaceUserPermissionConstant.PICTURE_EDIT)
```

需要：

```text
picture:edit
```

viewer 不行，editor/admin 可以。

管理成员：

```java
@SaSpaceCheckPermission(value = SpaceUserPermissionConstant.SPACE_USER_MANAGE)
```

需要：

```text
spaceUser:manage
```

只有 admin 可以。

查看图片：

```java
StpKit.SPACE.hasPermission(SpaceUserPermissionConstant.PICTURE_VIEW)
```

需要：

```text
picture:view
```

viewer/editor/admin 都可以。

---

## 16. 这套权限体系的整体链路

可以按这条线记：

```text
用户登录
-> StpKit.SPACE.login(userId)
-> 用户访问带 @SaSpaceCheckPermission 的接口
-> Sa-Token 拦截请求
-> 调用 StpInterfaceImpl#getPermissionList
-> 从请求中解析 spaceId / pictureId / spaceUserId
-> 查询 space / picture / space_user
-> 得到当前用户在当前空间的角色
-> SpaceUserAuthManager 根据角色查 JSON 权限配置
-> 返回权限列表给 Sa-Token
-> Sa-Token 判断是否包含接口要求的权限
-> 有权限放行，无权限拒绝
```

一句话总结：

```text
接口声明“我需要什么权限”
数据库保存“用户在空间里是什么角色”
JSON 配置声明“角色拥有哪些权限”
StpInterfaceImpl 动态计算“当前用户本次请求有哪些权限”
Sa-Token 负责最终拦截和放行
```

---

## 17. 为什么不直接在 Controller 里写 if 判断

简单项目可以这样写：

```java
if (!userId.equals(space.getUserId())) {
    throw new BusinessException(NO_AUTH);
}
```

但团队空间会越来越复杂。

因为不同接口需要不同权限：

```text
上传需要 picture:upload
编辑需要 picture:edit
删除需要 picture:delete
成员管理需要 spaceUser:manage
```

如果全部写在 Controller 里，会出现：

```text
每个接口都重复查空间
每个接口都重复查成员
每个接口都重复判断角色
权限逻辑散落到各处
后续新增角色很难维护
```

现在这套写法的好处是：

```text
Controller 只声明需要什么权限
权限计算统一放在 StpInterfaceImpl
角色和权限关系统一放 JSON
成员关系统一放 space_user 表
```

这更符合工程化。

---

## 18. 小白最容易混淆的几个点

### 18.1 `userRole` 和 `spaceRole` 不是一回事

| 名称 | 位置 | 含义 |
| --- | --- | --- |
| `userRole` | `user` 表 | 系统身份，比如 user/admin |
| `spaceRole` | `space_user` 表 | 某个空间里的角色，比如 viewer/editor/admin |

一个人可以是普通系统用户，但在某个团队空间里是 admin。

### 18.2 `admin` 有两种

```text
系统管理员：user.userRole = admin
空间管理员：space_user.spaceRole = admin
```

系统管理员是整个平台的管理员。

空间管理员只管理某个团队空间。

### 18.3 权限不是存在用户表里

用户表只存系统角色。

团队空间权限来自：

```text
space_user 表里的 spaceRole
+
spaceUserAuthConfig.json 里的角色权限配置
```

### 18.4 Sa-Token 不知道你的业务

Sa-Token 只知道：

```text
这个接口需要 picture:upload
你返回给我当前用户的权限列表
我判断里面有没有 picture:upload
```

至于权限列表怎么来的，是项目自己在 `StpInterfaceImpl` 里实现的。

---

## 19. 这套设计的优点

这套实现的优点很适合面试讲：

```text
1. 权限逻辑集中，不散落在 Controller
2. 角色和权限配置化，便于新增角色或调整权限
3. 支持空间维度隔离，同一用户在不同空间可拥有不同角色
4. 接口通过注解声明权限，可读性强
5. 与 Sa-Token 集成，复用成熟框架的登录态和注解拦截能力
6. space_user 表有唯一索引，避免同一用户在同一空间出现多个角色
```

---

## 20. 当前实现的边界和可优化点

### 20.1 权限每次可能查数据库

比如团队空间里，每次访问都可能查：

```text
space
space_user
picture
```

可以优化为：

```text
Redis 缓存 spaceId:userId -> permissionList
```

但要注意成员角色变更时必须删除缓存，否则可能越权。

### 20.2 JSON 权限配置是启动时读取的

`SpaceUserAuthManager` 里会读取：

```java
ResourceUtil.readUtf8Str("biz/spaceUserAuthConfig.json")
```

这说明角色权限配置主要是静态配置。优点是简单可靠，缺点是不能后台动态改权限。

### 20.3 公共图库、私有空间、团队空间逻辑混在一个方法里

现在能跑，但后续可以进一步拆成：

```text
PublicPicturePermissionResolver
PrivateSpacePermissionResolver
TeamSpacePermissionResolver
```

这样职责更清楚。

### 20.4 系统管理员和空间管理员要讲清楚

代码里部分场景对系统管理员放行，部分地方注释也提示可能存在私有空间权限处理边界。面试时不要说“所有地方都完美生产级”，应该说：

```text
当前实现覆盖了公共图库、私有空间、团队空间的主流程，后续可以进一步抽象权限解析器，并补权限缓存和缓存失效机制。
```

---

## 21. 最终一句话吃透

这套权限体系本质是：

```text
用 Sa-Token 做接口拦截，用自定义注解声明接口所需权限，用 space_user 表记录用户在空间里的角色，用 JSON 配置角色对应的权限，再用 StpInterfaceImpl 在每次请求时动态计算当前用户是否有资格操作当前空间资源。
```

再浓缩成面试表达：

```text
我在项目中基于 Sa-Token 扩展了一套空间级 RBAC 权限体系。系统登录后会将用户注册到自定义的 space 登录体系；接口通过 @SaSpaceCheckPermission 声明所需权限；权限校验时 Sa-Token 回调 StpInterfaceImpl，根据请求中的 spaceId、pictureId 或 spaceUserId 定位资源所属空间，再查询 space_user 表获取当前用户在该空间的角色，最后通过配置文件映射出角色权限列表，实现团队空间下 viewer、editor、admin 的差异化权限控制。
```
