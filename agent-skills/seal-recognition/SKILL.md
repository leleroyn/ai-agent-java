---
name: seal-recognition
description: |
  印章识别接口调用技能。对图片或 PDF 做印章检测，返回每枚印章的置信度、
  印章类型，以及印章上的公司名称（OCR）。当用户需要识别印章、检测印章
  数量/置信度/类型、或印章公司名时使用。（不返回印章图 base64。）
---

# 印章识别接口调用

`POST http://112.94.31.26:18080/ips/api/v3/seal/preprocess`

multipart 表单上传，文件类型（图片或 PDF）自动识别，无需指定。

## 请求参数

| 字段 | 类型 | 必填 | 默认 | 说明 |
|------|------|------|------|------|
| `file` | 文件 | 是 | - | 图片或 PDF |
| `return_ocr_text` | bool | 是 | `true` | 是否返回印章上的公司名称。**本技能调用时必须显式传 `return_ocr_text=true`**，否则拿不到公司名 |
| `return_seal_image` | bool | 是 | `false` | 是否返回印章图（base64 PNG）。**本技能固定传 `return_seal_image=false`**：base64 体积大、对文本输出无用且会占满上下文，故不取 |
| `init_confidence` | float | 否 | `0.5` | 检测置信度阈值 0~1；漏检调低（0.3），误检调高 |

## 响应

HTTP 200，**JSON 数组**，每个印章一个元素（空数组 = 未检出印章，不是错误）。
因固定 `return_seal_image=false`，响应不含 `seal_image_base64`：

```json
[
  {
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
# 检测并返回 置信度 + 类型 + 公司名（必须 return_ocr_text=true、return_seal_image=false）
curl -X POST "http://112.94.31.26:18080/ips/api/v3/seal/preprocess" \
  -F "file=@seal.jpg" \
  -F "return_ocr_text=true" \
  -F "return_seal_image=false"
```

Python（requests）：

```python
import requests

with open("seal.jpg", "rb") as f:
    r = requests.post(
        "http://112.94.31.26:18080/ips/api/v3/seal/preprocess",
        files={"file": f},
        data={"return_ocr_text": "true", "return_seal_image": "false"},
        timeout=120,
    )
r.raise_for_status()
seals = r.json()   # list[dict]：confidence / seal_type / ocr_result（无 base64）
```

## 注意

- **`return_ocr_text=true` 必须带**，否则拿不到 `ocr_result` 公司名；但它会明显变慢（每枚印章额外一次大模型推理）。
- **`return_seal_image=false` 固定带**，避免大 base64 占满上下文。若确需印章裁剪图，才去掉该参数（默认会返回）。
- **OCR 可能只读出局部字样**（例如只识别出"云南省"、或残缺的公司名），`ocr_result` 不可尽信；需要准确公司名时请结合原图人工复核，或改用 `extract_image_fields`（结构化抽字段）交叉核对。
- **识别印章优先用本接口**，不要用 `extract_image_fields`/`understand_image` 代替——通用图片工具给不出印章置信度/类型/检测数量。
- **每个文件单独调用**：接口一次只处理一个文件；多图请逐张调用再按图汇总。
- **PDF 会逐页检测**，结果按页顺序拼在同一个数组里（不区分来自哪一页，需要时自行按页拆）。
- **错误响应是纯文本不是 JSON**：非 200 时（如 400）直接读响应体文本作为错误信息；解析前先判断 `r.status_code == 200` 再 `json.loads`。
- 建议 `timeout` 给 120s 以上（PDF 页数多时更久）。
