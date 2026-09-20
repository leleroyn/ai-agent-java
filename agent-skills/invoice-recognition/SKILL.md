---
name: invoice-recognition
description: |
  发票识别接口调用技能。对图片或 PDF 做发票检测与信息提取，返回检测置信度
  和发票字段（发票号码、发票代码、金额等）或原始文本。当用户需要识别发票、
  提取发票信息/金额/发票号码时使用。
---

# 发票识别接口调用

`POST http://112.94.31.26:18080/ips/api/v3/invoice/preprocess`

multipart 表单上传，文件类型（图片或 PDF）自动识别，无需指定。

## 请求参数

| 字段 | 类型 | 必填 | 默认 | 说明 |
|------|------|------|------|------|
| `file` | 文件 | 是 | - | 图片或 PDF（PDF 只处理最后两页） |
| `return_ocr_text` | bool | 否 | `false` | 是否返回发票内容（会明显变慢） |
| `init_confidence` | float | 否 | `0.5` | 检测置信度阈值 0~1；漏检调低（0.3），误检调高 |

其余参数（`return_corp_image`、`resize`、`back_ground`）无需传，保持默认。

## 响应

HTTP 200，**JSON 数组，最多 1 个元素**（每个文件只返回一条结果）：

```json
[
  {
    "corp_image_base64": null,
    "confidence": 0.95,
    "invoice_type": 1,
    "ocr_text": "{\"发票号码\": \"2431...\", \"发票代码\": \"...\", \"发票类型\": \"...\", \"校验码\": \"...\", \"开票日期\": \"...\", \"购货方公司名称\": \"...\", \"销售方公司名称\": \"...\", \"发票金额\": \"...\", \"发票总金额\": \"...\"}",
    "source_text": null
  }
]
```

- `confidence`：发票检测置信度（0~1）
- `invoice_type`：恒为 `1`（识别到发票），可忽略
- `ocr_text`：JSON 字符串，含发票号码、发票代码、发票类型、校验码、开票日期、购货方公司名称、销售方公司名称、发票金额、发票总金额
  - 注意：**图片输入时 `ocr_text` 恒有值**（`return_ocr_text` 开关只对文字版 PDF 生效）
- `corp_image_base64`、`source_text`：恒为 `null`，可忽略

## 调用示例

```bash
# 基础用法：只检测是否有效发票
curl -X POST "http://112.94.31.26:18080/ips/api/v3/invoice/preprocess" \
  -F "file=@invoice.jpg" \
  -F "return_ocr_text=false"

# 提取发票信息
curl -X POST "http://112.94.31.26:18080/ips/api/v3/invoice/preprocess" \
  -F "file=@invoice.pdf" \
  -F "return_ocr_text=true"
```

Python（requests）：

```python
import requests

with open("invoice.jpg", "rb") as f:
    r = requests.post(
        "http://112.94.31.26:18080/ips/api/v3/invoice/preprocess",
        files={"file": f},
        data={"return_ocr_text": "true"},
        timeout=120,
    )
r.raise_for_status()
results = r.json()   # list[dict]，最多 1 个元素
```

## 注意

- **检测不到有效发票是错误不是空结果**：返回 HTTP 400，响应体为纯文本
  （`未检测到有效发票` / `发票信息提取不完整` / `PDF处理失败: ...`）；
  解析前先判断 `r.status_code == 200` 再 `json.loads`，非 200 时直接读响应体文本。
- **`return_ocr_text=true` 较慢**（内部走 OCR + 大模型提取），只要 confidence 时保持默认 false。
- 建议 `timeout` 给 120s 以上（PDF 更久）。
