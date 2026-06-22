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
