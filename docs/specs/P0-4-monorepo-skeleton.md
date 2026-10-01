# P0-4 monorepo 骨架

## 目标

`mvn -q test` 在仓库根目录跑通，五个 Java 模块是能编译的空工程，ArchUnit 规则能真正拦住违规依赖，CI 模板能跑。

## 依赖

- 前置任务：无。与 P0-1 完全独立，第一天就能开始
- 依赖的契约文件：无（`keel-common` 这一期只建空工程，模型生成是 P0-2）
- 依赖的外部组件及其真实行为：无

## 改哪些文件

```
pom.xml
keel-common/pom.xml
keel-gateway/pom.xml
keel-server/pom.xml
keel-audit/pom.xml
keel-spring-boot-starter/pom.xml
keel-*/src/main/java/com/keel/**
keel-*/src/test/java/com/keel/**
.github/workflows/keel-ci.yml
ci/**
deploy/{k3s,litellm,langfuse}/.gitkeep
sdk-python/.gitkeep · console/.gitkeep · templates/.gitkeep
```

`contracts/` 下的文件由 P0-1 负责，本任务不碰。

## 接口契约

仓库结构按技术文档第 05 节：

```
keel/
├── pom.xml                 # Maven 父工程
├── contracts/              # P0-1
├── keel-common/            # 契约模型、错误码、工具类
├── keel-gateway/           # WebFlux
├── keel-server/            # MVC + 虚拟线程
├── keel-audit/             # MVC + 虚拟线程
├── keel-spring-boot-starter/
├── sdk-python/  console/  templates/
├── deploy/{k3s,litellm,langfuse}/
├── ci/
└── docs/
```

Java 21 · Spring Boot 3.5.15 · MyBatis-Plus + Flyway · 根包 `com.keel`。

## 实现要点

- **ArchUnit 规则必须反向验证**。只写规则不验证，规则写错了也不会有人发现——测试照样绿。每条规则配一个**故意违规的样例类**放在 `src/test/java/.../archunit/violations/`，用 ArchUnit 的 API 断言「这个类确实被规则命中」。规则本身排除这个包。没有反向验证的 ArchUnit 规则等于没写。

  要覆盖的四条（来自 `.cursor/rules/java-service.mdc`）：跨模块使用对方 `mapper`、顶层出现 `controller/` `service/` `dao/` 包、`insight` 包写业务表、业务模块里直接 new HTTP 客户端绕过 `integration`。

- **keel-gateway 不能引入 `spring-boot-starter-web`**。它和 WebFlux 同时存在时 Spring Boot 会选 MVC，SSE 长连接的行为、线程模型、背压全部变掉，而且不会报错。在父 pom 里对 keel-gateway 模块加 `<exclusions>`，并写一条 ArchUnit 或 Maven Enforcer 规则钉死。这个坑调起来很贵，一开始就拦。

- **虚拟线程开了不等于没有 pinning**。`spring.threads.virtual.enabled=true` 之后，`synchronized` 块里做阻塞 IO 会 pin 住载体线程，并发一上来就退化。MyBatis 和部分 JDBC 驱动有这个问题。这一期只需要在 `keel-server`、`keel-audit` 的配置里开启并加一行注释说明，**不要**在骨架阶段就引入连接池调优——等 P1 有真实压测数据再说。

- 父 pom 只用 `dependencyManagement` 和 `pluginManagement` 统一版本，不要在父 pom 里直接写 `dependencies`，否则每个模块都会拖进不需要的依赖。

- 空工程要能启动：每个服务模块放一个 `*Application.java` 和一条冒烟测试（`@SpringBootTest` 能起上下文）。不要只建目录不建类，那样 ArchUnit 没有东西可扫，测试是假绿。

- **CI 模板这一期只做 Keel 自己的**（`.github/workflows/keel-ci.yml`：编译 + 单测 + ArchUnit）。智能体项目用的通用 CI 模板是 P2-3，不要在这里提前做。

## 验收标准

```bash
mvn -q -o test
mvn -q -o -pl :keel-gateway dependency:tree | grep -c spring-boot-starter-web   # 必须为 0
```

- [ ] 根目录 `mvn -q test` 通过，五个模块全部编译
- [ ] 每条 ArchUnit 规则都有对应的违规样例类，且能断言规则命中（反向验证）
- [ ] 把 `keel-server` 的一个 mapper 临时改成被 `keel-audit` 引用，`mvn test` 失败；改回后通过
- [ ] `keel-gateway` 的依赖树里没有 `spring-boot-starter-web`
- [ ] 每个服务模块的 `@SpringBootTest` 能起上下文
- [ ] CI 在 push 时触发并通过

## 明确不做

- 不写任何业务代码、controller、表结构。表结构是 P1-3
- 不做智能体项目的 CI 模板（P2-3）
- 不做 Docker 镜像和 K8s YAML，`deploy/` 下只建空目录占位
- 不初始化 `sdk-python/`、`console/` 的工程文件（分别在 P0-6、P1-15）
- 不做连接池、线程池调优
