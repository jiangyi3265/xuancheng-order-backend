# 玄成科技接单系统后端服务

玄成科技接单系统后端服务基于 RuoYi / Spring Boot 改造，负责订单、时间线、未读提醒、状态流转、微信推送配置和定时催办等核心业务能力，为管理后台和用户端提供 `/jiedan/**` API。

## 技术栈

- Java 17
- Spring Boot / RuoYi
- Maven 多模块工程
- MyBatis / MySQL
- Redis
- Quartz 定时任务
- Spring Security / Token 鉴权

## 关联仓库

| 子项目 | GitHub 仓库 | 说明 |
| --- | --- | --- |
| 后端服务 | [xuancheng-order-backend](https://github.com/jiangyi3265/xuancheng-order-backend) | 提供订单、提醒、时间线、系统配置等业务 API |
| 管理后台 | [xuancheng-order-admin](https://github.com/jiangyi3265/xuancheng-order-admin) | 面向老板和员工的接单管理后台 |
| 用户端 | [xuancheng-order-app](https://github.com/jiangyi3265/xuancheng-order-app) | 面向客户的公开需求提交入口 |

## 快速启动

```bash
# 准备 MySQL 与 Redis，并导入 deploy/xc_jiedan.sql
# 数据库连接可参考 deploy/application-druid.yml

mvn clean package -DskipTests
java -jar ruoyi-admin/target/ruoyi-admin.jar
```

默认服务端口为 `8080`。管理后台和用户端通过 Vite 代理访问 `/jiedan/**` 接口。

## 简历描述示例

基于 RuoYi 二次开发接单系统后端，设计订单流转、时间线、未读提醒和定时催办能力，支撑管理后台与客户提交端的完整业务闭环。

## 当前部署（2026-10-29）

- 管理后台：https://jiedanxt.oksja.cn/
- 客户端：https://jiedanxt.oksja.cn/client/
- 后端由同域名 `/jiedan/**`、`/login`、`/captchaImage` 代理，服务监听 `127.0.0.1:18091`。
- AI 改码流水线已移除；订单、客户账号、双向沟通、Bug 跟进和附件功能保留。
- GitHub Actions 负责构建并保存产物，已移除指向旧域名的自动部署。发布操作见后端 `deploy/production.md`。
- 正式管理员密码单独交付；客户账号由管理员在「客户账号」中创建。线上没有预置演示客户和演示订单。
