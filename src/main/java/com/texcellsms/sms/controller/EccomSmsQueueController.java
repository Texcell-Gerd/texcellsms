package com.texcellsms.sms.controller;


import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/eccom/sms-queue")
@Tag(name = "Eccom短信队列接口", description = "Eccom数据源 - 查询队列数据（任务队列列表+任务详情+任务详情导出）")
public class EccomSmsQueueController {


}