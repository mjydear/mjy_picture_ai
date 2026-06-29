# WebSocket + Disruptor 协同编辑事件分发深度解析

本文用于秋招复习，专门拆解云图库项目中 **WebSocket + Disruptor 支持协同编辑事件分发** 的完整实现逻辑。

相关核心代码：

- `mjy-picture-backend-ddd/src/main/java/com/yupi/yupicture/shared/websocket/WebSocketConfig.java`
- `mjy-picture-backend-ddd/src/main/java/com/yupi/yupicture/shared/websocket/WsHandshakeInterceptor.java`
- `mjy-picture-backend-ddd/src/main/java/com/yupi/yupicture/shared/websocket/PictureEditHandler.java`
- `mjy-picture-backend-ddd/src/main/java/com/yupi/yupicture/shared/websocket/disruptor/PictureEditEvent.java`
- `mjy-picture-backend-ddd/src/main/java/com/yupi/yupicture/shared/websocket/disruptor/PictureEditEventProducer.java`
- `mjy-picture-backend-ddd/src/main/java/com/yupi/yupicture/shared/websocket/disruptor/PictureEditEventWorkHandler.java`
- `mjy-picture-backend-ddd/src/main/java/com/yupi/yupicture/shared/websocket/disruptor/PictureEditEventDisruptorConfig.java`
- `mjy-picture-backend-ddd/src/main/java/com/yupi/yupicture/shared/websocket/model/PictureEditRequestMessage.java`
- `mjy-picture-backend-ddd/src/main/java/com/yupi/yupicture/shared/websocket/model/PictureEditResponseMessage.java`
- `mjy-picture-backend-ddd/src/main/java/com/yupi/yupicture/shared/websocket/model/PictureEditMessageTypeEnum.java`
- `mjy-picture-backend-ddd/src/main/java/com/yupi/yupicture/shared/websocket/model/PictureEditActionEnum.java`
- `mjy-picture-frontend/src/utils/pictureEditWebSocket.ts`
- `mjy-picture-frontend/src/components/ImageCropper.vue`

---

## 1. 先搞懂协同编辑是什么

协同编辑就是：

```text
多个用户同时打开同一个资源
一个用户做了操作
其他用户能实时看到这个操作或状态变化
```

在云图库项目里，这个资源就是“图片”。

比如团队空间里有一张图片：

```text
张三打开图片编辑页
李四也打开图片编辑页
张三点击开始编辑
李四马上看到张三正在编辑
张三旋转图片
李四的页面也同步旋转
张三退出编辑
李四马上看到编辑状态释放
```

这就是图片协同编辑。

注意：当前项目实现的是“编辑事件同步”，不是类似腾讯文档那种复杂文本协同算法。

它主要同步的是：

```text
谁进入编辑
谁退出编辑
谁执行了旋转、缩放等图片编辑动作
```

---

## 2. 为什么普通 HTTP 不适合做协同编辑

普通 HTTP 是请求响应模型：

```text
前端发请求
后端处理
后端返回结果
连接结束
```

这种模式适合：

```text
查询图片列表
上传图片
删除图片
保存表单
```

但协同编辑需要“实时推送”：

```text
张三做了操作
后端要立刻通知李四和王五
```

如果只用 HTTP，就只能让前端不停轮询：

```text
每隔 1 秒问一次后端：有没有新操作？
每隔 1 秒问一次后端：有没有新操作？
每隔 1 秒问一次后端：有没有新操作？
```

这会带来几个问题：

```text
1. 实时性差，最多要等下一次轮询
2. 请求很多，浪费服务器资源
3. 用户越多，压力越大
4. 后端不能主动把消息推给前端
```

所以这里使用 WebSocket。

WebSocket 的特点是：

```text
前端和后端建立一条长连接
连接保持不断
前端可以随时发消息给后端
后端也可以主动推消息给前端
```

一句话理解：

```text
HTTP 像发邮件，发一次回一次。
WebSocket 像打电话，电话接通后双方可以随时说话。
```

---

## 3. Disruptor 又是干什么的

WebSocket 解决的是“实时通信”问题。

但协同编辑还会遇到另一个问题：

```text
短时间内可能有大量编辑消息涌入
```

比如用户连续点击：

```text
放大
放大
旋转
缩小
旋转
```

如果 WebSocket 收到消息后直接处理所有业务逻辑，会让 WebSocket 处理线程变重。

项目里用 Disruptor 做一层事件队列：

```text
WebSocket 收到消息
-> 把消息封装成事件
-> 投递到 Disruptor 环形队列
-> 消费者异步取出事件并处理
```

Disruptor 可以先简单理解为：

```text
一个高性能的内存消息队列
```

它的优势是：

```text
1. 低延迟
2. 高吞吐
3. 适合处理高频事件
4. 生产者和消费者解耦
```

在这个项目里，Disruptor 的价值是：

```text
WebSocket 负责收发消息
Disruptor 负责排队和异步消费编辑事件
PictureEditHandler 负责维护编辑状态和广播结果
```

---

## 4. 这套功能的整体链路

可以先按这条线记：

```text
前端打开图片编辑器
-> 创建 WebSocket 连接，携带 pictureId
-> 后端握手拦截器校验用户登录和图片编辑权限
-> 连接成功后，把当前 WebSocketSession 加入 pictureId 对应的会话集合
-> 用户点击进入编辑、退出编辑、旋转、缩放
-> 前端发送 ENTER_EDIT / EXIT_EDIT / EDIT_ACTION 消息
-> PictureEditHandler 收到消息
-> PictureEditEventProducer 把消息发布到 Disruptor RingBuffer
-> PictureEditEventWorkHandler 消费事件
-> 根据消息类型调用对应处理方法
-> 后端广播消息给同一张图片下的其他用户
-> 其他前端收到消息后同步 UI
```

一句话总结：

```text
WebSocket 负责实时双向通信，Disruptor 负责把编辑消息变成异步事件流，后端按 pictureId 把事件广播给同一张图片的其他用户。
```

---

## 5. 前端是怎么建立 WebSocket 连接的

前端封装了一个工具类：`PictureEditWebSocket`。

位置是：

```text
mjy-picture-frontend/src/utils/pictureEditWebSocket.ts
```

核心逻辑是：

```ts
const url = `${DEV_BASE_URL}/api/ws/picture/edit?pictureId=${this.pictureId}`
this.socket = new WebSocket(url)
```

也就是说，前端连接时会携带：

```text
pictureId
```

比如当前编辑的图片 id 是 1001，那么连接地址类似：

```text
ws://localhost:8123/api/ws/picture/edit?pictureId=1001
```

为什么要带 `pictureId`？

因为后端必须知道：

```text
这个 WebSocket 连接属于哪一张图片
后续应该把它放到哪个图片房间里
后续广播消息时应该发给哪些用户
```

---

## 6. WebSocket 后端入口在哪里

后端入口在 `WebSocketConfig`。

核心代码是：

```java
registry.addHandler(pictureEditHandler, "/ws/picture/edit")
        .addInterceptors(wsHandshakeInterceptor)
        .setAllowedOrigins("*");
```

这段代码的意思是：

```text
注册一个 WebSocket 地址：/ws/picture/edit
这个地址由 PictureEditHandler 处理
建立连接前先经过 WsHandshakeInterceptor 拦截器
允许跨域连接
```

可以把它理解成：

```text
/ws/picture/edit 是协同编辑专用通道
PictureEditHandler 是真正处理消息的人
WsHandshakeInterceptor 是门卫
```

---

## 7. 握手拦截器为什么很重要

WebSocket 在正式通信前，会先经历一次“握手”。

项目通过 `WsHandshakeInterceptor` 在握手阶段做校验。

它的核心职责是：

```text
连接还没建立前，先判断这个用户有没有资格进入协同编辑房间
```

如果不做握手校验，会有风险：

```text
未登录用户也能连接
没有权限的人也能监听编辑事件
不是团队空间的图片也能进入协同编辑
恶意用户可以伪造 pictureId 建立连接
```

所以握手阶段必须先把门守住。

---

## 8. 握手阶段具体校验什么

`WsHandshakeInterceptor#beforeHandshake` 里主要做了这些事：

```text
1. 从请求参数中获取 pictureId
2. 如果 pictureId 为空，拒绝握手
3. 获取当前登录用户
4. 如果用户未登录，拒绝握手
5. 根据 pictureId 查询图片
6. 如果图片不存在，拒绝握手
7. 如果图片属于某个空间，查询空间
8. 如果空间不存在，拒绝握手
9. 如果空间不是团队空间，拒绝握手
10. 查询当前用户在该空间下的权限列表
11. 如果不包含 picture:edit，拒绝握手
12. 校验通过后，把 user、userId、pictureId 放入 WebSocketSession 属性
```

核心判断是：

```java
if (!permissionList.contains(SpaceUserPermissionConstant.PICTURE_EDIT)) {
    return false;
}
```

也就是说：

```text
只有具备 picture:edit 权限的用户，才能建立协同编辑 WebSocket 连接
```

这和前面 Sa-Token 空间 RBAC 是连起来的。

Sa-Token 那套权限体系解决的是：

```text
用户在团队空间里有没有 picture:edit 权限
```

WebSocket 握手这里复用了这个权限判断结果：

```text
有 picture:edit -> 允许进入协同编辑
没有 picture:edit -> 拒绝连接
```

---

## 9. WebSocketSession 里存了什么

握手校验通过后，后端会把一些信息放入 `attributes`：

```java
attributes.put("user", loginUser);
attributes.put("userId", loginUser.getId());
attributes.put("pictureId", Long.valueOf(pictureId));
```

这些属性后面会进入 `WebSocketSession`。

也就是说，每条 WebSocket 连接都知道：

```text
当前连接属于哪个用户
当前连接对应哪张图片
```

后续处理消息时，就不用让前端每次都重复传 userId 和 pictureId。

这也更安全：

```text
userId 来自后端登录态，不信任前端传入
pictureId 来自握手阶段校验过的参数
```

---

## 10. 连接成功后发生了什么

连接建立成功后，会进入 `PictureEditHandler#afterConnectionEstablished`。

项目维护了两个核心内存结构。

第一个是：

```java
private final Map<Long, Long> pictureEditingUsers = new ConcurrentHashMap<>();
```

它表示：

```text
key: pictureId
value: 当前正在编辑这张图片的 userId
```

比如：

```text
1001 -> 10
```

意思是：

```text
图片 1001 当前正在被用户 10 编辑
```

第二个是：

```java
private final Map<Long, Set<WebSocketSession>> pictureSessions = new ConcurrentHashMap<>();
```

它表示：

```text
key: pictureId
value: 正在打开这张图片编辑页面的所有 WebSocketSession
```

比如：

```text
1001 -> [张三的连接, 李四的连接, 王五的连接]
```

可以理解为：

```text
每一张图片都是一个协同编辑房间
pictureSessions 保存每个房间里的所有在线连接
```

---

## 11. 为什么用 ConcurrentHashMap

WebSocket 是多用户并发访问的。

可能同时发生：

```text
张三连接进来
李四连接进来
王五断开连接
张三开始编辑
李四收到广播
```

如果用普通 `HashMap`，在并发读写时可能出现线程安全问题。

所以项目使用：

```java
ConcurrentHashMap
```

它适合多线程场景下并发读写。

这里的两个 Map 都是共享状态：

```text
pictureEditingUsers 保存编辑锁状态
pictureSessions 保存连接房间状态
```

所以用线程安全集合是合理的。

---

## 12. 前端会发送哪些消息

消息类型定义在 `PictureEditMessageTypeEnum`。

目前有 5 种：

| 类型 | 含义 |
| --- | --- |
| `INFO` | 普通通知 |
| `ERROR` | 错误消息 |
| `ENTER_EDIT` | 进入编辑状态 |
| `EXIT_EDIT` | 退出编辑状态 |
| `EDIT_ACTION` | 执行编辑动作 |

前端真正主动发给后端的主要是：

```text
ENTER_EDIT
EXIT_EDIT
EDIT_ACTION
```

进入编辑时：

```json
{
  "type": "ENTER_EDIT"
}
```

退出编辑时：

```json
{
  "type": "EXIT_EDIT"
}
```

执行编辑动作时：

```json
{
  "type": "EDIT_ACTION",
  "editAction": "ROTATE_LEFT"
}
```

---

## 13. 支持哪些编辑动作

编辑动作定义在 `PictureEditActionEnum`。

目前有 4 种：

| 动作 | 含义 |
| --- | --- |
| `ZOOM_IN` | 放大 |
| `ZOOM_OUT` | 缩小 |
| `ROTATE_LEFT` | 左旋 |
| `ROTATE_RIGHT` | 右旋 |

所以当前协同编辑不是同步整张图片文件，而是同步“操作事件”。

比如张三点击左旋：

```text
张三本地图片先左旋
前端发送 EDIT_ACTION + ROTATE_LEFT
后端广播给其他人
其他人的前端收到 ROTATE_LEFT
其他人的本地图片也执行左旋
```

这叫“操作同步”。

---

## 14. PictureEditHandler 收到消息后做什么

当前端发送 WebSocket 消息后，会进入：

```java
PictureEditHandler#handleTextMessage
```

核心逻辑是：

```java
PictureEditRequestMessage pictureEditRequestMessage = JSONUtil.toBean(message.getPayload(), PictureEditRequestMessage.class);
User user = (User) session.getAttributes().get("user");
Long pictureId = (Long) session.getAttributes().get("pictureId");
pictureEditEventProducer.publishEvent(pictureEditRequestMessage, session, user, pictureId);
```

也就是说，`PictureEditHandler` 收到消息后没有直接处理业务，而是：

```text
1. 把 JSON 字符串转成 PictureEditRequestMessage
2. 从 session 里取出 user 和 pictureId
3. 调用 PictureEditEventProducer 发布事件
```

这里体现了一个重要设计：

```text
WebSocket 收消息层不直接做复杂业务
而是把消息变成事件，交给 Disruptor 异步消费
```

---

## 15. PictureEditRequestMessage 是什么

`PictureEditRequestMessage` 是前端发给后端的消息模型。

核心字段是：

```java
private String type;
private String editAction;
```

其中：

```text
type 表示消息类型
editAction 表示具体编辑动作
```

举例：

```json
{
  "type": "EDIT_ACTION",
  "editAction": "ZOOM_IN"
}
```

意思是：

```text
当前用户执行了一次放大操作
```

---

## 16. PictureEditResponseMessage 是什么

`PictureEditResponseMessage` 是后端广播给前端的消息模型。

核心字段是：

```java
private String type;
private String message;
private String editAction;
private UserVO user;
```

它比请求消息多了：

```text
message：给用户看的提示，比如“张三开始编辑图片”
user：是谁触发了这个事件
```

比如后端广播：

```json
{
  "type": "ENTER_EDIT",
  "message": "用户 张三 开始编辑图片",
  "user": {
    "id": "10",
    "userName": "张三"
  }
}
```

其他用户收到后，就可以在页面上展示：

```text
张三正在编辑
```

---

## 17. Disruptor 是怎么初始化的

Disruptor 配置在 `PictureEditEventDisruptorConfig`。

核心代码是：

```java
int bufferSize = 1024 * 256;

Disruptor<PictureEditEvent> disruptor = new Disruptor<>(
        PictureEditEvent::new,
        bufferSize,
        ThreadFactoryBuilder.create().setNamePrefix("pictureEditEventDisruptor").build()
);

disruptor.handleEventsWithWorkerPool(pictureEditEventWorkHandler);
disruptor.start();
```

这里做了几件事：

```text
1. 创建一个大小为 1024 * 256 的 RingBuffer
2. 指定事件类型是 PictureEditEvent
3. 指定线程名前缀 pictureEditEventDisruptor
4. 设置消费者 pictureEditEventWorkHandler
5. 启动 Disruptor
```

RingBuffer 可以理解成：

```text
一个环形数组队列
生产者往里面放事件
消费者从里面取事件
```

为什么叫环形？

因为它不会像普通队列那样一直扩容，而是在固定大小的数组里循环使用位置。

---

## 18. PictureEditEvent 里装了什么

`PictureEditEvent` 是 Disruptor 里流转的事件对象。

它包含：

```java
private PictureEditRequestMessage pictureEditRequestMessage;
private WebSocketSession session;
private User user;
private Long pictureId;
```

也就是说，一次编辑事件需要的信息都在里面：

```text
前端发来的消息是什么
是谁发的
来自哪个 WebSocket 连接
操作的是哪张图片
```

为什么要把这些都封装起来？

因为事件进入 Disruptor 后，消费者处理时需要完整上下文。

如果缺少 user，就不知道是谁操作的。

如果缺少 pictureId，就不知道广播给哪个房间。

如果缺少 session，就不知道是否要排除当前发送者。

---

## 19. 生产者怎么发布事件

生产者是 `PictureEditEventProducer`。

核心逻辑是：

```java
RingBuffer<PictureEditEvent> ringBuffer = pictureEditEventDisruptor.getRingBuffer();
long next = ringBuffer.next();
PictureEditEvent pictureEditEvent = ringBuffer.get(next);
pictureEditEvent.setPictureEditRequestMessage(pictureEditRequestMessage);
pictureEditEvent.setSession(session);
pictureEditEvent.setUser(user);
pictureEditEvent.setPictureId(pictureId);
ringBuffer.publish(next);
```

可以拆成 4 步理解：

```text
1. 拿到 RingBuffer
2. 申请一个可写位置 next
3. 把消息、session、user、pictureId 填到事件对象里
4. 发布这个位置，让消费者可以处理
```

这就是生产者的职责：

```text
只负责把编辑消息放进队列，不负责具体业务处理
```

---

## 20. 消费者怎么处理事件

消费者是 `PictureEditEventWorkHandler`。

核心逻辑是：

```java
String type = pictureEditRequestMessage.getType();
PictureEditMessageTypeEnum pictureEditMessageTypeEnum = PictureEditMessageTypeEnum.getEnumByValue(type);

switch (pictureEditMessageTypeEnum) {
    case ENTER_EDIT:
        pictureEditHandler.handleEnterEditMessage(...);
        break;
    case EXIT_EDIT:
        pictureEditHandler.handleExitEditMessage(...);
        break;
    case EDIT_ACTION:
        pictureEditHandler.handleEditActionMessage(...);
        break;
    default:
        session.sendMessage(...);
        break;
}
```

消费者主要做的是“分发”：

```text
ENTER_EDIT 交给进入编辑处理方法
EXIT_EDIT 交给退出编辑处理方法
EDIT_ACTION 交给编辑动作处理方法
其他类型返回错误消息
```

这就把消息处理流程拆清楚了：

```text
PictureEditHandler 负责收到 WebSocket 消息
PictureEditEventProducer 负责投递事件
PictureEditEventWorkHandler 负责消费和分发事件
PictureEditHandler 的具体方法负责维护状态和广播
```

---

## 21. ENTER_EDIT 怎么处理

进入编辑状态由：

```java
handleEnterEditMessage
```

处理。

核心逻辑是：

```text
1. 判断 pictureEditingUsers 里有没有这张图片
2. 如果没有，说明当前没人正在编辑
3. 把 pictureId -> userId 放入 pictureEditingUsers
4. 构造 ENTER_EDIT 响应消息
5. 广播给这张图片下的所有用户
```

代码逻辑类似：

```java
if (!pictureEditingUsers.containsKey(pictureId)) {
    pictureEditingUsers.put(pictureId, user.getId());
    broadcastToPicture(pictureId, pictureEditResponseMessage);
}
```

这相当于一个简单的“编辑锁”：

```text
同一张图片同一时间只允许一个用户真正进入编辑状态
```

如果张三已经在编辑，李四再发送 `ENTER_EDIT`，就不会成功抢占。

---

## 22. EDIT_ACTION 怎么处理

编辑动作由：

```java
handleEditActionMessage
```

处理。

核心流程是：

```text
1. 从 pictureEditingUsers 获取当前图片正在编辑的 userId
2. 从请求消息里获取 editAction
3. 判断 editAction 是否是合法枚举
4. 判断当前发消息的人是不是正在编辑的人
5. 如果是，就构造 EDIT_ACTION 响应消息
6. 广播给同一张图片下的其他用户
```

为什么要判断“当前发消息的人是不是正在编辑的人”？

因为只有拿到编辑锁的人才能真正发编辑动作。

否则可能出现：

```text
张三正在编辑
李四只是围观
李四却伪造 EDIT_ACTION 消息让其他人同步旋转
```

所以代码会判断：

```java
if (editingUserId != null && editingUserId.equals(user.getId())) {
    broadcastToPicture(pictureId, pictureEditResponseMessage, session);
}
```

为什么广播时要排除当前 session？

因为当前用户本地已经执行了这个动作。

比如张三点击左旋：

```text
张三页面自己已经左旋了一次
后端只需要通知李四和王五左旋
如果再发回张三，张三可能重复左旋一次
```

所以这里调用的是：

```java
broadcastToPicture(pictureId, pictureEditResponseMessage, session);
```

意思是：

```text
广播给同一张图片的所有连接，但排除当前发送者
```

---

## 23. EXIT_EDIT 怎么处理

退出编辑由：

```java
handleExitEditMessage
```

处理。

核心流程是：

```text
1. 获取当前图片正在编辑的 userId
2. 判断退出的人是不是当前编辑者
3. 如果是，就从 pictureEditingUsers 删除这张图片的编辑状态
4. 构造 EXIT_EDIT 响应消息
5. 广播给这张图片下的所有用户
```

它的作用是释放编辑锁：

```text
张三退出编辑后
其他人才能进入编辑
```

如果不释放，会出现：

```text
张三明明不编辑了
系统还认为张三占着编辑状态
李四永远进不去编辑
```

---

## 24. 连接断开时为什么也要退出编辑

用户不一定会正常点击“退出编辑”。

可能发生：

```text
直接关闭浏览器
刷新页面
电脑断网
浏览器崩溃
```

所以项目在：

```java
afterConnectionClosed
```

里主动调用：

```java
handleExitEditMessage(null, session, user, pictureId);
```

这样可以保证：

```text
只要连接断开，就尝试释放当前用户占用的编辑状态
```

然后还会把当前 session 从 `pictureSessions` 里删除。

如果这个图片房间已经没人了，就删除整个房间：

```text
sessionSet.remove(session)
如果 sessionSet 为空
pictureSessions.remove(pictureId)
```

最后广播：

```text
用户 xxx 离开编辑
```

---

## 25. 广播是怎么实现的

广播方法是：

```java
broadcastToPicture
```

它根据 `pictureId` 找到对应的 session 集合：

```java
Set<WebSocketSession> sessionSet = pictureSessions.get(pictureId);
```

然后把响应对象转成 JSON：

```java
String message = objectMapper.writeValueAsString(pictureEditResponseMessage);
TextMessage textMessage = new TextMessage(message);
```

最后遍历所有打开的 WebSocketSession：

```java
for (WebSocketSession session : sessionSet) {
    if (excludeSession != null && session.equals(excludeSession)) {
        continue;
    }
    if (session.isOpen()) {
        session.sendMessage(textMessage);
    }
}
```

它支持两种模式：

```text
广播给所有人
广播给除当前发送者之外的其他人
```

进入编辑、退出编辑、用户加入离开，通常广播给所有人。

编辑动作，通常排除当前发送者。

---

## 26. 为什么 Long 要序列化成 String

广播时项目配置了：

```java
module.addSerializer(Long.class, ToStringSerializer.instance);
module.addSerializer(Long.TYPE, ToStringSerializer.instance);
```

这是为了避免前端 JavaScript 数字精度丢失。

Java 的 Long 可能很大，比如：

```text
1900000000000000000
```

JavaScript 的 number 对特别大的整数不安全，可能丢精度。

所以后端把 Long 转成字符串：

```json
{
  "id": "1900000000000000000"
}
```

这也是很多 Java 后端项目给前端返回 id 时常见的处理方式。

---

## 27. 前端收到广播后怎么同步

前端工具类里有：

```ts
this.socket.onmessage = (event) => {
  const message = JSON.parse(event.data)
  const type = message.type
  this.triggerEvent(type, message)
}
```

意思是：

```text
收到后端消息
解析 JSON
根据 type 触发对应前端事件
```

比如收到：

```json
{
  "type": "EDIT_ACTION",
  "editAction": "ROTATE_LEFT"
}
```

前端就触发 `EDIT_ACTION` 事件。

在图片裁剪组件里，再根据 `editAction` 执行对应操作：

```text
ROTATE_LEFT -> 左旋
ROTATE_RIGHT -> 右旋
ZOOM_IN -> 放大
ZOOM_OUT -> 缩小
```

所以前后端形成闭环：

```text
用户 A 操作图片
前端 A 发 WebSocket 消息
后端通过 Disruptor 处理事件
后端广播给用户 B/C
前端 B/C 收到消息并执行同样操作
```

---

## 28. 和 Sa-Token 权限体系是什么关系

Sa-Token 空间 RBAC 解决的是：

```text
用户有没有权限操作这张图片
```

WebSocket + Disruptor 解决的是：

```text
有权限的用户进入协同编辑后，编辑事件怎么实时分发
```

两者关系是：

```text
Sa-Token / SpaceUserAuthManager 负责准入权限
WebSocket 负责实时连接
Disruptor 负责事件异步分发
PictureEditHandler 负责编辑状态和广播
```

在握手阶段，WebSocket 会校验：

```text
当前用户是否有 picture:edit 权限
```

这个权限来自前面空间 RBAC：

```text
space_user 表记录用户在空间里的角色
spaceUserAuthConfig.json 记录角色有哪些权限
SpaceUserAuthManager 根据角色返回权限列表
```

所以可以这样理解：

```text
先用权限体系判断你能不能进门
进门之后再用 WebSocket + Disruptor 同步你的编辑动作
```

---

## 29. 这套设计为什么不是直接在 Controller 里做

Controller 适合处理普通 HTTP 请求：

```text
新增
删除
修改
查询
```

但协同编辑需要长连接和服务端主动推送。

如果放在 Controller 里，会遇到：

```text
HTTP 请求处理完就结束，不能持续推送
无法维护长期在线会话
难以知道哪些用户正在看同一张图片
难以实时广播编辑动作
```

所以项目把它拆到 WebSocket 模块：

```text
WebSocketConfig 负责注册连接地址
WsHandshakeInterceptor 负责连接前鉴权
PictureEditHandler 负责连接生命周期和广播
Disruptor 负责高频事件异步分发
```

这样职责更清楚。

---

## 30. 小白最容易混淆的几个点

### 30.1 WebSocket 和 HTTP 不是一回事

HTTP 是：

```text
请求一次，响应一次
```

WebSocket 是：

```text
建立长连接，双方随时通信
```

协同编辑需要后端主动通知其他用户，所以用 WebSocket。

### 30.2 WebSocket 不等于权限校验

WebSocket 只是通信通道。

谁能连接，仍然需要业务权限判断。

项目在握手阶段校验登录态和 `picture:edit` 权限。

### 30.3 Disruptor 不负责网络通信

Disruptor 不会直接给前端发消息。

它只负责：

```text
把编辑消息排队
再交给消费者处理
```

真正发 WebSocket 消息的是 `PictureEditHandler#broadcastToPicture`。

### 30.4 pictureSessions 不是数据库

`pictureSessions` 是内存 Map。

它只记录当前 JVM 里有哪些 WebSocket 连接。

服务重启后就没了。

多台服务器部署时，每台机器只能看到自己的连接。

### 30.5 当前实现不是 CRDT / OT

CRDT 和 OT 是复杂协同算法，常见于多人同时编辑文本。

当前项目是简单图片动作同步：

```text
同一时间一个人拿编辑锁
其他人同步他的操作
```

所以它不是多人同时自由编辑，而是更简单可靠的“单编辑者 + 多观察者同步”。

---

## 31. 这套设计的优点

这套实现的优点很适合面试讲：

```text
1. 使用 WebSocket 实现服务端主动推送，满足协同编辑实时性要求。
2. 握手阶段做登录和空间编辑权限校验，避免无权限用户进入协同编辑房间。
3. 按 pictureId 维护会话集合，实现图片维度的消息隔离。
4. 使用 ConcurrentHashMap 维护在线会话和编辑状态，适配多用户并发访问。
5. 使用 Disruptor 将 WebSocket 收消息和事件处理解耦，适合高频编辑事件分发。
6. 使用 ENTER_EDIT / EXIT_EDIT / EDIT_ACTION 消息类型，让协同编辑协议清晰可扩展。
7. 编辑动作广播排除当前发送者，避免本地重复执行同一操作。
8. 连接断开时自动释放编辑状态，降低编辑锁长期占用风险。
```

---

## 32. 当前实现的边界和可优化点

### 32.1 编辑状态只存在本机内存

当前：

```text
pictureEditingUsers
pictureSessions
```

都存在 JVM 内存里。

单机部署没问题。

但如果后端部署多台机器：

```text
用户 A 连到机器 1
用户 B 连到机器 2
机器 1 不知道机器 2 的 session
机器 2 也不知道机器 1 的编辑状态
```

这时就会出现跨节点协同问题。

优化方向：

```text
用 Redis 保存编辑锁
用 Redis Pub/Sub 或 MQ 做跨节点事件广播
或者引入专门的 WebSocket 网关
```

### 32.2 当前是单编辑者模型

现在同一张图片同一时间只允许一个用户编辑。

优点是简单，不容易冲突。

缺点是协同能力有限。

如果未来要支持多人同时编辑，就需要引入更复杂的冲突解决机制，比如：

```text
操作版本号
操作顺序控制
OT
CRDT
```

### 32.3 消息可靠性可以增强

当前 WebSocket 消息更偏实时通知。

如果消息丢了，可能出现某个用户页面状态没有同步。

可优化为：

```text
给消息加 sequenceId
前端按顺序应用事件
断线重连后拉取最新图片状态
重要操作落库或写入消息日志
```

### 32.4 Disruptor 异常处理可以增强

当前消费者处理异常的监控还可以更完善。

可优化为：

```text
增加异常日志上下文
增加消费失败指标
增加告警
增加消息类型校验和参数校验
```

### 32.5 前端连接地址可以配置化

当前前端工具类里写了：

```ts
const DEV_BASE_URL = "ws://localhost:8123";
```

后续可以改成环境变量：

```text
VITE_WS_BASE_URL
```

这样开发环境、测试环境、生产环境切换更方便。

---

## 33. 面试怎么讲这套功能

可以这样讲第一层：

```text
我在项目里实现了团队空间图片协同编辑功能。前端进入图片编辑页时，会通过 WebSocket 连接后端，并携带 pictureId。后端在握手阶段会校验用户登录态、图片是否存在、图片是否属于团队空间，以及当前用户是否具备 picture:edit 权限。校验通过后，后端会按照 pictureId 维护 WebSocketSession 集合，相当于给每张图片建立一个协同编辑房间。
```

再讲第二层：

```text
用户的进入编辑、退出编辑、旋转、缩放等操作不会在 WebSocket 收消息线程里直接处理，而是先封装成 PictureEditEvent 投递到 Disruptor 的 RingBuffer 中。随后由 WorkHandler 消费事件，根据消息类型分发到对应处理方法，最后广播给同一张图片下的其他 WebSocket 客户端。这样实现了网络连接层和编辑事件处理层的解耦，也能更好支撑高频编辑事件分发。
```

再讲设计价值：

```text
这套设计本质上是事件驱动模型：WebSocket 负责实时双向通信，Disruptor 负责高性能事件排队和异步消费，ConcurrentHashMap 负责维护图片维度的在线会话和编辑锁，从而实现团队空间下的实时协同编辑。
```

最后讲不足和优化：

```text
当前实现适合单机部署和简单图片动作同步。如果要支持多实例部署，需要把编辑锁和广播能力从本机内存迁移到 Redis 或 MQ；如果要支持真正多人同时编辑，则需要引入 OT 或 CRDT 等冲突解决算法。
```

---

## 34. 最终一句话吃透

这套协同编辑体系本质是：

```text
用 WebSocket 维持前后端实时连接，用握手拦截器校验用户是否有图片编辑权限，用 ConcurrentHashMap 按 pictureId 管理在线会话和当前编辑者，用 Disruptor 把前端发来的编辑消息变成异步事件流，最后由消费者调用 PictureEditHandler 将编辑状态和编辑动作广播给同一张图片下的其他用户。
```

再浓缩成面试表达：

```text
我在云图库项目中基于 WebSocket + Disruptor 实现了团队空间图片协同编辑事件分发。前端进入编辑页后会携带 pictureId 建立 WebSocket 连接，后端在握手阶段校验登录态、团队空间和 picture:edit 权限；连接成功后按 pictureId 维护会话集合和当前编辑者。用户的进入编辑、退出编辑、旋转、缩放等操作会被封装成 PictureEditEvent 投递到 Disruptor RingBuffer，由 WorkHandler 异步消费并分发到对应处理逻辑，再广播给同一张图片下的其他客户端，从而实现低延迟的协同编辑状态同步。
```
