---
name: seal-recognition
description: |
  印章识别接口调用技能。对图片或 PDF 做印章检测，返回每枚印章的印章图
  （base64）、置信度、印章类型，以及印章上的公司名称（OCR）。当用户需要
  识别印章、检测印章数量/置信度/类型、提取印章图或印章公司名时使用。
---

# 印章识别接口调用

`POST http://112.94.31.26:18080/ips/api/v3/seal/preprocess`

multipart 表单上传，文件类型（图片或 PDF）自动识别，无需指定。

## 请求参数

| 字段 | 类型 | 必填 | 默认 | 说明 |
|------|------|------|------|------|
| `file` | 文件 | 是 | - | 图片或 PDF |
| `return_ocr_text` | bool | 是 | `true` | 是否返回印章上的公司名称。**本技能调用时必须显式传 `return_ocr_text=true`**，否则拿不到公司名 |
| `return_seal_image` | bool | 否 | `true` | 是否返回印章图（base64 PNG）；不需要图可传 `false` 减小响应 |
| `init_confidence` | float | 否 | `0.5` | 检测置信度阈值 0~1；漏检调低（0.3），误检调高 |

## 响应

HTTP 200，**JSON 数组**，每个印章一个元素（空数组 = 未检出印章，不是错误）：

```json
[
  {
    "seal_image_base64": "iVBORw0KGgo...（PNG 图，return_seal_image=false 时为 null）",
    "confidence": 0.92,
    "seal_type": 1,
    "ocr_result": "某某有限公司"
  }
]
```

- `confidence`：检测置信度（0~1）
- `seal_type`：`1`=圆形红色印章，`2`=圆形灰色印章
- `ocr_result`：印章上的公司名称（`return_ocr_text=true` 且印章为圆形时有值；非圆形或未识别到文字时为 `""`）

## 调用示例

```bash
# 检测并返回印章图 + 置信度 + 类型 + 公司名（必须带 return_ocr_text=true）
curl -X POST "http://112.94.31.26:18080/ips/api/v3/seal/preprocess" \
  -F "file=@seal.jpg" \
  -F "return_ocr_text=true"
```

Python（requests）：

```python
import requests

with open("seal.jpg", "rb") as f:
    r = requests.post(
        "http://112.94.31.26:18080/ips/api/v3/seal/preprocess",
        files={"file": f},
        data={"return_ocr_text": "true"},
        timeout=120,
    )
r.raise_for_status()
seals = r.json()   # list[dict]：seal_image_base64 / confidence / seal_type / ocr_result
```

## 注意

- **`return_ocr_text=true` 必须带**，否则拿不到 `ocr_result` 公司名；但它会明显变慢（每枚印章额外一次大模型推理）。
- **OCR 可能只读出局部字样**（例如只识别出"云南省"、或残缺的公司名），`ocr_result` 不可尽信；需要准确公司名时请结合原图人工复核，或改用 `extract_image_fields`（结构化抽字段）交叉核对。
- **PDF 会逐页检测**，结果按页顺序拼在同一个数组里。
- **错误响应是纯文本不是 JSON**：非 200 时（如 400）直接读响应体文本作为错误信息；解析前先判断 `r.status_code == 200` 再 `json.loads`。
- `seal_image_base64` 体积可能较大，解析时不要把它整段打印进上下文；只取你需要的字段。
- 建议 `timeout` 给 120s 以上（PDF 页数多时更久）。
