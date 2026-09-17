/**
 * 块大小对齐实验（`/api/diag/rag/align`）的**假数据**。
 *
 * ⚠️ 这个文件由 `scripts/gen-align-mock.mjs` 从真后端的一次实测响应生成，
 * **不要手改数字** —— 手改就会与"界面上的解读文字"脱钩，而自检断言不了
 * "文字和数字是否一致"（rag-routes.mjs 开头那段说的就是这个坑）。
 * 要更新就重新跑一次真后端 + 生成脚本。
 *
 * 数值来源：真启动的 Spring Boot 后端，GET /api/diag/rag/align
 *   43 条用例 / 4 份文档 / 三路检索 / 六档预算 / HTTP 200 / 184585 字节 / 2.8s（2026-09-18 重测）
 *
 * 它承载的结论（阶段二「块大小对齐实验」，完整说明见 `docs/项目功能点与代码索引.md` 的 3.5 节）：
 *   块大小对齐之后（202.8 vs 202.4 token，差 0.41），**固定字数硬切全面占优** ——
 *   Recall@5 / MRR / token 总量 / 重复率都更优，且**三路检索下方向一致**。
 *
 * 所以这份假数据里，`pairs` 的**左（对齐硬切）必须一直优于右（结构无面包屑）**：
 * 如果哪天它反过来了，那界面就在自相矛盾 —— 自检脚本里有一条断言专门盯着这件事。
 */

export const ALIGNMENT = {
  "evalSetLocation": "classpath:rag/eval-set.tsv",
  "caseCount": 43,
  "sampleSufficient": true,
  "documents": 4,
  "failedDocuments": [],
  "dimension": "平均块大小",
  "alignTargetTokens": 202.3886,
  "sweepRange": [
    326,
    544
  ],
  "sweep": [
    {
      "fixedSize": 326,
      "chunkCount": 240,
      "avgChunkTokens": 153.7542,
      "totalTokens": 36901,
      "duplicationRatio": 0.1434,
      "gapToTarget": -48.6344
    },
    {
      "fixedSize": 353,
      "chunkCount": 222,
      "avgChunkTokens": 166.2793,
      "totalTokens": 36914,
      "duplicationRatio": 0.1438,
      "gapToTarget": -36.1093
    },
    {
      "fixedSize": 380,
      "chunkCount": 206,
      "avgChunkTokens": 178.7087,
      "totalTokens": 36814,
      "duplicationRatio": 0.1407,
      "gapToTarget": -23.6799
    },
    {
      "fixedSize": 407,
      "chunkCount": 192,
      "avgChunkTokens": 191.5208,
      "totalTokens": 36772,
      "duplicationRatio": 0.1394,
      "gapToTarget": -10.8678
    },
    {
      "fixedSize": 434,
      "chunkCount": 181,
      "avgChunkTokens": 202.8011,
      "totalTokens": 36707,
      "duplicationRatio": 0.1374,
      "gapToTarget": 0.4125
    },
    {
      "fixedSize": 461,
      "chunkCount": 171,
      "avgChunkTokens": 214.3275,
      "totalTokens": 36650,
      "duplicationRatio": 0.1356,
      "gapToTarget": 11.9389
    },
    {
      "fixedSize": 488,
      "chunkCount": 161,
      "avgChunkTokens": 227.441,
      "totalTokens": 36618,
      "duplicationRatio": 0.1346,
      "gapToTarget": 25.0524
    },
    {
      "fixedSize": 515,
      "chunkCount": 152,
      "avgChunkTokens": 239.7237,
      "totalTokens": 36438,
      "duplicationRatio": 0.129,
      "gapToTarget": 37.3351
    },
    {
      "fixedSize": 542,
      "chunkCount": 144,
      "avgChunkTokens": 253.4722,
      "totalTokens": 36500,
      "duplicationRatio": 0.1309,
      "gapToTarget": 51.0836
    }
  ],
  "alignedFixedSize": 434,
  "alignedMeasuredAvg": 202.8011,
  "alignedGapTokens": 0.4125,
  "refinementProbes": 9,
  "pairs": [
    {
      "fieldDescription": "平均块大小 == 202.4 token（结构分块关掉面包屑，正文也对齐） ｜ 检索=lexical-bm25 ｜ 左=fixed 434字/43重叠 → 实测平均 202.8 token ｜ 右=structural max-tokens=400 无面包屑",
      "dimension": "平均块大小",
      "reports": [
        {
          "strategy": "fixed-aligned-202",
          "chunkCount": 181,
          "avgChunkTokens": 202.8011,
          "maxChunkTokens": 291,
          "metrics": {
            "strategy": "fixed-aligned-202",
            "caseCount": 43,
            "chunkCount": 181,
            "avgChunkTokens": 202.8011,
            "recallAt": {
              "1": 0.5116,
              "3": 0.7209,
              "5": 0.8372,
              "10": 0.9302
            },
            "mrr": 0.6469,
            "contextPrecisionAt": {
              "1": 0.5116,
              "3": 0.3488,
              "5": 0.293,
              "10": 0.2116
            },
            "firstThreeHitsTokens": 649.4884,
            "topKTokens": 2150.7209,
            "budgetAlignment": {
              "1000": {
                "budget": 1000,
                "recall": 0.7907,
                "mrr": 0.626,
                "avgTokensUsed": 884.6512,
                "avgHitCount": 4.1628
              },
              "2000": {
                "budget": 2000,
                "recall": 0.9302,
                "mrr": 0.6469,
                "avgTokensUsed": 1902.0233,
                "avgHitCount": 8.8837
              },
              "3000": {
                "budget": 3000,
                "recall": 0.9302,
                "mrr": 0.6469,
                "avgTokensUsed": 2150.7209,
                "avgHitCount": 10
              },
              "4000": {
                "budget": 4000,
                "recall": 0.9302,
                "mrr": 0.6469,
                "avgTokensUsed": 2150.7209,
                "avgHitCount": 10
              },
              "6000": {
                "budget": 6000,
                "recall": 0.9302,
                "mrr": 0.6469,
                "avgTokensUsed": 2150.7209,
                "avgHitCount": 10
              },
              "8000": {
                "budget": 8000,
                "recall": 0.9302,
                "mrr": 0.6469,
                "avgTokensUsed": 2150.7209,
                "avgHitCount": 10
              }
            },
            "misses": [],
            "perTag": {}
          },
          "tokenCost": {
            "totalTokens": 36707,
            "duplicationRatio": 0.1374,
            "minTokens": 73,
            "undersizedChunks": 2
          }
        },
        {
          "strategy": "structural-nobc",
          "chunkCount": 193,
          "avgChunkTokens": 202.3886,
          "maxChunkTokens": 389,
          "metrics": {
            "strategy": "structural-nobc",
            "caseCount": 43,
            "chunkCount": 193,
            "avgChunkTokens": 202.3886,
            "recallAt": {
              "1": 0.3953,
              "3": 0.6047,
              "5": 0.7442,
              "10": 0.8372
            },
            "mrr": 0.5244,
            "contextPrecisionAt": {
              "1": 0.3953,
              "3": 0.2558,
              "5": 0.2233,
              "10": 0.1721
            },
            "firstThreeHitsTokens": 628.3721,
            "topKTokens": 2256.0233,
            "budgetAlignment": {
              "1000": {
                "budget": 1000,
                "recall": 0.7209,
                "mrr": 0.5047,
                "avgTokensUsed": 857,
                "avgHitCount": 4.3023
              },
              "2000": {
                "budget": 2000,
                "recall": 0.814,
                "mrr": 0.5215,
                "avgTokensUsed": 1811.1628,
                "avgHitCount": 8.4186
              },
              "3000": {
                "budget": 3000,
                "recall": 0.8372,
                "mrr": 0.5244,
                "avgTokensUsed": 2241.093,
                "avgHitCount": 9.9535
              },
              "4000": {
                "budget": 4000,
                "recall": 0.8372,
                "mrr": 0.5244,
                "avgTokensUsed": 2256.0233,
                "avgHitCount": 10
              },
              "6000": {
                "budget": 6000,
                "recall": 0.8372,
                "mrr": 0.5244,
                "avgTokensUsed": 2256.0233,
                "avgHitCount": 10
              },
              "8000": {
                "budget": 8000,
                "recall": 0.8372,
                "mrr": 0.5244,
                "avgTokensUsed": 2256.0233,
                "avgHitCount": 10
              }
            },
            "misses": [],
            "perTag": {}
          },
          "tokenCost": {
            "totalTokens": 39061,
            "duplicationRatio": 0.2103,
            "minTokens": 11,
            "undersizedChunks": 43
          }
        }
      ],
      "deltas": {
        "avgChunkTokens": {
          "structural-nobc": -0.4125
        },
        "recall@1": {
          "structural-nobc": -11.63
        },
        "duplicationRatio": {
          "structural-nobc": 0.0729
        },
        "recall@3": {
          "structural-nobc": -11.62
        },
        "recall@5": {
          "structural-nobc": -9.3
        },
        "chunkCount": {
          "structural-nobc": 12
        },
        "contextPrecision@1": {
          "structural-nobc": -11.63
        },
        "topKTokens": {
          "structural-nobc": 105.3024
        },
        "contextPrecision@3": {
          "structural-nobc": -9.3
        },
        "contextPrecision@5": {
          "structural-nobc": -6.97
        },
        "mrr": {
          "structural-nobc": -12.25
        },
        "contextPrecision@10": {
          "structural-nobc": -3.95
        },
        "recall@10": {
          "structural-nobc": -9.3
        },
        "firstThreeHitsTokens": {
          "structural-nobc": -21.1163
        }
      },
      "notes": [
        "这是「最干净的一组」：块大小对齐、正文也对齐（关掉面包屑），于是唯一的差异只剩「切点选在哪」",
        "对照组的块大小是按实测收敛出来的，与目标差 0.41 token —— 不是完全相等"
      ]
    },
    {
      "fieldDescription": "平均块大小 == 202.4 token（结构分块关掉面包屑，正文也对齐） ｜ 检索=vector-local ｜ 左=fixed 434字/43重叠 → 实测平均 202.8 token ｜ 右=structural max-tokens=400 无面包屑",
      "dimension": "平均块大小",
      "reports": [
        {
          "strategy": "fixed-aligned-202",
          "chunkCount": 181,
          "avgChunkTokens": 202.8011,
          "maxChunkTokens": 291,
          "metrics": {
            "strategy": "fixed-aligned-202",
            "caseCount": 43,
            "chunkCount": 181,
            "avgChunkTokens": 202.8011,
            "recallAt": {
              "1": 0.2791,
              "3": 0.3721,
              "5": 0.4651,
              "10": 0.5814
            },
            "mrr": 0.358,
            "contextPrecisionAt": {
              "1": 0.2791,
              "3": 0.1783,
              "5": 0.1395,
              "10": 0.1093
            },
            "firstThreeHitsTokens": 625.4884,
            "topKTokens": 2080.7442,
            "budgetAlignment": {},
            "misses": [],
            "perTag": {}
          },
          "tokenCost": {
            "totalTokens": 36707,
            "duplicationRatio": 0.1374,
            "minTokens": 73,
            "undersizedChunks": 2
          }
        },
        {
          "strategy": "structural-nobc",
          "chunkCount": 193,
          "avgChunkTokens": 202.3886,
          "maxChunkTokens": 389,
          "metrics": {
            "strategy": "structural-nobc",
            "caseCount": 43,
            "chunkCount": 193,
            "avgChunkTokens": 202.3886,
            "recallAt": {
              "1": 0.186,
              "3": 0.3256,
              "5": 0.3488,
              "10": 0.4419
            },
            "mrr": 0.26,
            "contextPrecisionAt": {
              "1": 0.186,
              "3": 0.124,
              "5": 0.0884,
              "10": 0.0698
            },
            "firstThreeHitsTokens": 606.4186,
            "topKTokens": 2135.4651,
            "budgetAlignment": {},
            "misses": [],
            "perTag": {}
          },
          "tokenCost": {
            "totalTokens": 39061,
            "duplicationRatio": 0.2103,
            "minTokens": 11,
            "undersizedChunks": 43
          }
        }
      ],
      "deltas": {
        "avgChunkTokens": {
          "structural-nobc": -0.4125
        },
        "recall@1": {
          "structural-nobc": -9.31
        },
        "duplicationRatio": {
          "structural-nobc": 0.0729
        },
        "recall@3": {
          "structural-nobc": -4.65
        },
        "recall@5": {
          "structural-nobc": -11.63
        },
        "chunkCount": {
          "structural-nobc": 12
        },
        "contextPrecision@1": {
          "structural-nobc": -9.31
        },
        "topKTokens": {
          "structural-nobc": 54.7209
        },
        "contextPrecision@3": {
          "structural-nobc": -5.43
        },
        "contextPrecision@5": {
          "structural-nobc": -5.11
        },
        "mrr": {
          "structural-nobc": -9.8
        },
        "contextPrecision@10": {
          "structural-nobc": -3.95
        },
        "recall@10": {
          "structural-nobc": -13.95
        },
        "firstThreeHitsTokens": {
          "structural-nobc": -19.0698
        }
      },
      "notes": [
        "这是「最干净的一组」：块大小对齐、正文也对齐（关掉面包屑），于是唯一的差异只剩「切点选在哪」",
        "对照组的块大小是按实测收敛出来的，与目标差 0.41 token —— 不是完全相等"
      ]
    },
    {
      "fieldDescription": "平均块大小 == 202.4 token（结构分块关掉面包屑，正文也对齐） ｜ 检索=hybrid-rrf ｜ 左=fixed 434字/43重叠 → 实测平均 202.8 token ｜ 右=structural max-tokens=400 无面包屑",
      "dimension": "平均块大小",
      "reports": [
        {
          "strategy": "fixed-aligned-202",
          "chunkCount": 181,
          "avgChunkTokens": 202.8011,
          "maxChunkTokens": 291,
          "metrics": {
            "strategy": "fixed-aligned-202",
            "caseCount": 43,
            "chunkCount": 181,
            "avgChunkTokens": 202.8011,
            "recallAt": {
              "1": 0.4186,
              "3": 0.5814,
              "5": 0.7442,
              "10": 0.8837
            },
            "mrr": 0.5515,
            "contextPrecisionAt": {
              "1": 0.4186,
              "3": 0.2558,
              "5": 0.2558,
              "10": 0.1953
            },
            "firstThreeHitsTokens": 629.2326,
            "topKTokens": 2113.9767,
            "budgetAlignment": {},
            "misses": [],
            "perTag": {}
          },
          "tokenCost": {
            "totalTokens": 36707,
            "duplicationRatio": 0.1374,
            "minTokens": 73,
            "undersizedChunks": 2
          }
        },
        {
          "strategy": "structural-nobc",
          "chunkCount": 193,
          "avgChunkTokens": 202.3886,
          "maxChunkTokens": 389,
          "metrics": {
            "strategy": "structural-nobc",
            "caseCount": 43,
            "chunkCount": 193,
            "avgChunkTokens": 202.3886,
            "recallAt": {
              "1": 0.3488,
              "3": 0.4651,
              "5": 0.6744,
              "10": 0.814
            },
            "mrr": 0.4694,
            "contextPrecisionAt": {
              "1": 0.3488,
              "3": 0.186,
              "5": 0.186,
              "10": 0.1372
            },
            "firstThreeHitsTokens": 624.7209,
            "topKTokens": 2078.5814,
            "budgetAlignment": {},
            "misses": [],
            "perTag": {}
          },
          "tokenCost": {
            "totalTokens": 39061,
            "duplicationRatio": 0.2103,
            "minTokens": 11,
            "undersizedChunks": 43
          }
        }
      ],
      "deltas": {
        "avgChunkTokens": {
          "structural-nobc": -0.4125
        },
        "recall@1": {
          "structural-nobc": -6.98
        },
        "duplicationRatio": {
          "structural-nobc": 0.0729
        },
        "recall@3": {
          "structural-nobc": -11.63
        },
        "recall@5": {
          "structural-nobc": -6.98
        },
        "chunkCount": {
          "structural-nobc": 12
        },
        "contextPrecision@1": {
          "structural-nobc": -6.98
        },
        "topKTokens": {
          "structural-nobc": -35.3953
        },
        "contextPrecision@3": {
          "structural-nobc": -6.98
        },
        "contextPrecision@5": {
          "structural-nobc": -6.98
        },
        "mrr": {
          "structural-nobc": -8.21
        },
        "contextPrecision@10": {
          "structural-nobc": -5.81
        },
        "recall@10": {
          "structural-nobc": -6.97
        },
        "firstThreeHitsTokens": {
          "structural-nobc": -4.5117
        }
      },
      "notes": [
        "这是「最干净的一组」：块大小对齐、正文也对齐（关掉面包屑），于是唯一的差异只剩「切点选在哪」",
        "对照组的块大小是按实测收敛出来的，与目标差 0.41 token —— 不是完全相等"
      ]
    },
    {
      "fieldDescription": "平均块大小 == 200.6 token（结构分块默认带面包屑） ｜ 检索=lexical-bm25 ｜ 左=fixed 427字/42重叠 → 实测平均 201.1 token ｜ 右=structural max-tokens=400 +面包屑",
      "dimension": "平均块大小",
      "reports": [
        {
          "strategy": "fixed-aligned-201",
          "chunkCount": 182,
          "avgChunkTokens": 201.0549,
          "maxChunkTokens": 288,
          "metrics": {
            "strategy": "fixed-aligned-201",
            "caseCount": 43,
            "chunkCount": 182,
            "avgChunkTokens": 201.0549,
            "recallAt": {
              "1": 0.4651,
              "3": 0.7442,
              "5": 0.7674,
              "10": 0.8837
            },
            "mrr": 0.6192,
            "contextPrecisionAt": {
              "1": 0.4651,
              "3": 0.3488,
              "5": 0.2698,
              "10": 0.1953
            },
            "firstThreeHitsTokens": 633.9535,
            "topKTokens": 2118.8837,
            "budgetAlignment": {
              "1000": {
                "budget": 1000,
                "recall": 0.7674,
                "mrr": 0.6027,
                "avgTokensUsed": 875.1163,
                "avgHitCount": 4.186
              },
              "2000": {
                "budget": 2000,
                "recall": 0.8837,
                "mrr": 0.6192,
                "avgTokensUsed": 1903.7674,
                "avgHitCount": 9.0233
              },
              "3000": {
                "budget": 3000,
                "recall": 0.8837,
                "mrr": 0.6192,
                "avgTokensUsed": 2118.8837,
                "avgHitCount": 10
              },
              "4000": {
                "budget": 4000,
                "recall": 0.8837,
                "mrr": 0.6192,
                "avgTokensUsed": 2118.8837,
                "avgHitCount": 10
              },
              "6000": {
                "budget": 6000,
                "recall": 0.8837,
                "mrr": 0.6192,
                "avgTokensUsed": 2118.8837,
                "avgHitCount": 10
              },
              "8000": {
                "budget": 8000,
                "recall": 0.8837,
                "mrr": 0.6192,
                "avgTokensUsed": 2118.8837,
                "avgHitCount": 10
              }
            },
            "misses": [],
            "perTag": {}
          },
          "tokenCost": {
            "totalTokens": 36592,
            "duplicationRatio": 0.1338,
            "minTokens": 107,
            "undersizedChunks": 0
          }
        },
        {
          "strategy": "structural",
          "chunkCount": 234,
          "avgChunkTokens": 200.6026,
          "maxChunkTokens": 390,
          "metrics": {
            "strategy": "structural",
            "caseCount": 43,
            "chunkCount": 234,
            "avgChunkTokens": 200.6026,
            "recallAt": {
              "1": 0.4186,
              "3": 0.6047,
              "5": 0.7674,
              "10": 0.8372
            },
            "mrr": 0.5452,
            "contextPrecisionAt": {
              "1": 0.4186,
              "3": 0.2791,
              "5": 0.2512,
              "10": 0.1953
            },
            "firstThreeHitsTokens": 588.5814,
            "topKTokens": 2016.3488,
            "budgetAlignment": {
              "1000": {
                "budget": 1000,
                "recall": 0.7442,
                "mrr": 0.5302,
                "avgTokensUsed": 902.8837,
                "avgHitCount": 4.6512
              },
              "2000": {
                "budget": 2000,
                "recall": 0.8372,
                "mrr": 0.5452,
                "avgTokensUsed": 1817.4884,
                "avgHitCount": 9.1628
              },
              "3000": {
                "budget": 3000,
                "recall": 0.8372,
                "mrr": 0.5452,
                "avgTokensUsed": 2016.3488,
                "avgHitCount": 10
              },
              "4000": {
                "budget": 4000,
                "recall": 0.8372,
                "mrr": 0.5452,
                "avgTokensUsed": 2016.3488,
                "avgHitCount": 10
              },
              "6000": {
                "budget": 6000,
                "recall": 0.8372,
                "mrr": 0.5452,
                "avgTokensUsed": 2016.3488,
                "avgHitCount": 10
              },
              "8000": {
                "budget": 8000,
                "recall": 0.8372,
                "mrr": 0.5452,
                "avgTokensUsed": 2016.3488,
                "avgHitCount": 10
              }
            },
            "misses": [],
            "perTag": {}
          },
          "tokenCost": {
            "totalTokens": 46941,
            "duplicationRatio": 0.4545,
            "minTokens": 19,
            "undersizedChunks": 17
          }
        }
      ],
      "deltas": {
        "avgChunkTokens": {
          "structural": -0.4523
        },
        "recall@1": {
          "structural": -4.65
        },
        "duplicationRatio": {
          "structural": 0.3207
        },
        "recall@3": {
          "structural": -13.95
        },
        "recall@5": {
          "structural": 0
        },
        "chunkCount": {
          "structural": 52
        },
        "contextPrecision@1": {
          "structural": -4.65
        },
        "topKTokens": {
          "structural": -102.5349
        },
        "contextPrecision@3": {
          "structural": -6.97
        },
        "contextPrecision@5": {
          "structural": -1.86
        },
        "mrr": {
          "structural": -7.4
        },
        "contextPrecision@10": {
          "structural": 0
        },
        "recall@10": {
          "structural": -4.65
        },
        "firstThreeHitsTokens": {
          "structural": -45.3721
        }
      },
      "notes": [
        "这一组对齐的是「渲染后」的块大小：结构分块的正文仍然比对照组小（差的就是每块拼一遍的标题路径），所以它不能单独用来判断「切点」的优劣 ——判断切点请用关掉面包屑的那一组"
      ]
    },
    {
      "fieldDescription": "平均块大小 == 200.6 token（结构分块默认带面包屑） ｜ 检索=vector-local ｜ 左=fixed 427字/42重叠 → 实测平均 201.1 token ｜ 右=structural max-tokens=400 +面包屑",
      "dimension": "平均块大小",
      "reports": [
        {
          "strategy": "fixed-aligned-201",
          "chunkCount": 182,
          "avgChunkTokens": 201.0549,
          "maxChunkTokens": 288,
          "metrics": {
            "strategy": "fixed-aligned-201",
            "caseCount": 43,
            "chunkCount": 182,
            "avgChunkTokens": 201.0549,
            "recallAt": {
              "1": 0.3023,
              "3": 0.3953,
              "5": 0.4884,
              "10": 0.5814
            },
            "mrr": 0.3738,
            "contextPrecisionAt": {
              "1": 0.3023,
              "3": 0.1628,
              "5": 0.1349,
              "10": 0.1047
            },
            "firstThreeHitsTokens": 619.3721,
            "topKTokens": 2055.7209,
            "budgetAlignment": {},
            "misses": [],
            "perTag": {}
          },
          "tokenCost": {
            "totalTokens": 36592,
            "duplicationRatio": 0.1338,
            "minTokens": 107,
            "undersizedChunks": 0
          }
        },
        {
          "strategy": "structural",
          "chunkCount": 234,
          "avgChunkTokens": 200.6026,
          "maxChunkTokens": 390,
          "metrics": {
            "strategy": "structural",
            "caseCount": 43,
            "chunkCount": 234,
            "avgChunkTokens": 200.6026,
            "recallAt": {
              "1": 0.2791,
              "3": 0.4884,
              "5": 0.5814,
              "10": 0.6744
            },
            "mrr": 0.408,
            "contextPrecisionAt": {
              "1": 0.2791,
              "3": 0.186,
              "5": 0.1442,
              "10": 0.1116
            },
            "firstThreeHitsTokens": 617.8837,
            "topKTokens": 2042.4419,
            "budgetAlignment": {},
            "misses": [],
            "perTag": {}
          },
          "tokenCost": {
            "totalTokens": 46941,
            "duplicationRatio": 0.4545,
            "minTokens": 19,
            "undersizedChunks": 17
          }
        }
      ],
      "deltas": {
        "avgChunkTokens": {
          "structural": -0.4523
        },
        "recall@1": {
          "structural": -2.32
        },
        "duplicationRatio": {
          "structural": 0.3207
        },
        "recall@3": {
          "structural": 9.31
        },
        "recall@5": {
          "structural": 9.3
        },
        "chunkCount": {
          "structural": 52
        },
        "contextPrecision@1": {
          "structural": -2.32
        },
        "topKTokens": {
          "structural": -13.279
        },
        "contextPrecision@3": {
          "structural": 2.32
        },
        "contextPrecision@5": {
          "structural": 0.93
        },
        "mrr": {
          "structural": 3.42
        },
        "contextPrecision@10": {
          "structural": 0.69
        },
        "recall@10": {
          "structural": 9.3
        },
        "firstThreeHitsTokens": {
          "structural": -1.4884
        }
      },
      "notes": [
        "这一组对齐的是「渲染后」的块大小：结构分块的正文仍然比对照组小（差的就是每块拼一遍的标题路径），所以它不能单独用来判断「切点」的优劣 ——判断切点请用关掉面包屑的那一组"
      ]
    },
    {
      "fieldDescription": "平均块大小 == 200.6 token（结构分块默认带面包屑） ｜ 检索=hybrid-rrf ｜ 左=fixed 427字/42重叠 → 实测平均 201.1 token ｜ 右=structural max-tokens=400 +面包屑",
      "dimension": "平均块大小",
      "reports": [
        {
          "strategy": "fixed-aligned-201",
          "chunkCount": 182,
          "avgChunkTokens": 201.0549,
          "maxChunkTokens": 288,
          "metrics": {
            "strategy": "fixed-aligned-201",
            "caseCount": 43,
            "chunkCount": 182,
            "avgChunkTokens": 201.0549,
            "recallAt": {
              "1": 0.3953,
              "3": 0.5116,
              "5": 0.6977,
              "10": 0.814
            },
            "mrr": 0.5099,
            "contextPrecisionAt": {
              "1": 0.3953,
              "3": 0.2326,
              "5": 0.2233,
              "10": 0.1767
            },
            "firstThreeHitsTokens": 618,
            "topKTokens": 2074.0698,
            "budgetAlignment": {},
            "misses": [],
            "perTag": {}
          },
          "tokenCost": {
            "totalTokens": 36592,
            "duplicationRatio": 0.1338,
            "minTokens": 107,
            "undersizedChunks": 0
          }
        },
        {
          "strategy": "structural",
          "chunkCount": 234,
          "avgChunkTokens": 200.6026,
          "maxChunkTokens": 390,
          "metrics": {
            "strategy": "structural",
            "caseCount": 43,
            "chunkCount": 234,
            "avgChunkTokens": 200.6026,
            "recallAt": {
              "1": 0.4419,
              "3": 0.6512,
              "5": 0.7674,
              "10": 0.8605
            },
            "mrr": 0.5739,
            "contextPrecisionAt": {
              "1": 0.4419,
              "3": 0.2713,
              "5": 0.2233,
              "10": 0.1628
            },
            "firstThreeHitsTokens": 638.5349,
            "topKTokens": 2031.2093,
            "budgetAlignment": {},
            "misses": [],
            "perTag": {}
          },
          "tokenCost": {
            "totalTokens": 46941,
            "duplicationRatio": 0.4545,
            "minTokens": 19,
            "undersizedChunks": 17
          }
        }
      ],
      "deltas": {
        "avgChunkTokens": {
          "structural": -0.4523
        },
        "recall@1": {
          "structural": 4.66
        },
        "duplicationRatio": {
          "structural": 0.3207
        },
        "recall@3": {
          "structural": 13.96
        },
        "recall@5": {
          "structural": 6.97
        },
        "chunkCount": {
          "structural": 52
        },
        "contextPrecision@1": {
          "structural": 4.66
        },
        "topKTokens": {
          "structural": -42.8605
        },
        "contextPrecision@3": {
          "structural": 3.87
        },
        "contextPrecision@5": {
          "structural": 0
        },
        "mrr": {
          "structural": 6.4
        },
        "contextPrecision@10": {
          "structural": -1.39
        },
        "recall@10": {
          "structural": 4.65
        },
        "firstThreeHitsTokens": {
          "structural": 20.5349
        }
      },
      "notes": [
        "这一组对齐的是「渲染后」的块大小：结构分块的正文仍然比对照组小（差的就是每块拼一遍的标题路径），所以它不能单独用来判断「切点」的优劣 ——判断切点请用关掉面包屑的那一组"
      ]
    },
    {
      "fieldDescription": "块更大的效应有多大（结构分块无面包屑 vs 与默认组同大小的硬切） ｜ 检索=lexical-bm25 ｜ 左=structural max-tokens=400 无面包屑 ｜ 右=fixed 427字/42重叠 → 实测平均 201.1 token",
      "dimension": "等 token 预算",
      "reports": [
        {
          "strategy": "structural-nobc",
          "chunkCount": 193,
          "avgChunkTokens": 202.3886,
          "maxChunkTokens": 389,
          "metrics": {
            "strategy": "structural-nobc",
            "caseCount": 43,
            "chunkCount": 193,
            "avgChunkTokens": 202.3886,
            "recallAt": {
              "1": 0.3953,
              "3": 0.6047,
              "5": 0.7442,
              "10": 0.8372
            },
            "mrr": 0.5244,
            "contextPrecisionAt": {
              "1": 0.3953,
              "3": 0.2558,
              "5": 0.2233,
              "10": 0.1721
            },
            "firstThreeHitsTokens": 628.3721,
            "topKTokens": 2256.0233,
            "budgetAlignment": {
              "1000": {
                "budget": 1000,
                "recall": 0.7209,
                "mrr": 0.5047,
                "avgTokensUsed": 857,
                "avgHitCount": 4.3023
              },
              "2000": {
                "budget": 2000,
                "recall": 0.814,
                "mrr": 0.5215,
                "avgTokensUsed": 1811.1628,
                "avgHitCount": 8.4186
              },
              "3000": {
                "budget": 3000,
                "recall": 0.8372,
                "mrr": 0.5244,
                "avgTokensUsed": 2241.093,
                "avgHitCount": 9.9535
              },
              "4000": {
                "budget": 4000,
                "recall": 0.8372,
                "mrr": 0.5244,
                "avgTokensUsed": 2256.0233,
                "avgHitCount": 10
              },
              "6000": {
                "budget": 6000,
                "recall": 0.8372,
                "mrr": 0.5244,
                "avgTokensUsed": 2256.0233,
                "avgHitCount": 10
              },
              "8000": {
                "budget": 8000,
                "recall": 0.8372,
                "mrr": 0.5244,
                "avgTokensUsed": 2256.0233,
                "avgHitCount": 10
              }
            },
            "misses": [],
            "perTag": {}
          },
          "tokenCost": {
            "totalTokens": 39061,
            "duplicationRatio": 0.2103,
            "minTokens": 11,
            "undersizedChunks": 43
          }
        },
        {
          "strategy": "fixed-aligned-201",
          "chunkCount": 182,
          "avgChunkTokens": 201.0549,
          "maxChunkTokens": 288,
          "metrics": {
            "strategy": "fixed-aligned-201",
            "caseCount": 43,
            "chunkCount": 182,
            "avgChunkTokens": 201.0549,
            "recallAt": {
              "1": 0.4651,
              "3": 0.7442,
              "5": 0.7674,
              "10": 0.8837
            },
            "mrr": 0.6192,
            "contextPrecisionAt": {
              "1": 0.4651,
              "3": 0.3488,
              "5": 0.2698,
              "10": 0.1953
            },
            "firstThreeHitsTokens": 633.9535,
            "topKTokens": 2118.8837,
            "budgetAlignment": {
              "1000": {
                "budget": 1000,
                "recall": 0.7674,
                "mrr": 0.6027,
                "avgTokensUsed": 875.1163,
                "avgHitCount": 4.186
              },
              "2000": {
                "budget": 2000,
                "recall": 0.8837,
                "mrr": 0.6192,
                "avgTokensUsed": 1903.7674,
                "avgHitCount": 9.0233
              },
              "3000": {
                "budget": 3000,
                "recall": 0.8837,
                "mrr": 0.6192,
                "avgTokensUsed": 2118.8837,
                "avgHitCount": 10
              },
              "4000": {
                "budget": 4000,
                "recall": 0.8837,
                "mrr": 0.6192,
                "avgTokensUsed": 2118.8837,
                "avgHitCount": 10
              },
              "6000": {
                "budget": 6000,
                "recall": 0.8837,
                "mrr": 0.6192,
                "avgTokensUsed": 2118.8837,
                "avgHitCount": 10
              },
              "8000": {
                "budget": 8000,
                "recall": 0.8837,
                "mrr": 0.6192,
                "avgTokensUsed": 2118.8837,
                "avgHitCount": 10
              }
            },
            "misses": [],
            "perTag": {}
          },
          "tokenCost": {
            "totalTokens": 36592,
            "duplicationRatio": 0.1338,
            "minTokens": 107,
            "undersizedChunks": 0
          }
        }
      ],
      "deltas": {
        "avgChunkTokens": {
          "fixed-aligned-201": -1.3337
        },
        "recall@1": {
          "fixed-aligned-201": 6.98
        },
        "duplicationRatio": {
          "fixed-aligned-201": -0.0765
        },
        "recall@3": {
          "fixed-aligned-201": 13.95
        },
        "recall@5": {
          "fixed-aligned-201": 2.32
        },
        "chunkCount": {
          "fixed-aligned-201": -11
        },
        "contextPrecision@1": {
          "fixed-aligned-201": 6.98
        },
        "topKTokens": {
          "fixed-aligned-201": -137.1396
        },
        "contextPrecision@3": {
          "fixed-aligned-201": 9.3
        },
        "contextPrecision@5": {
          "fixed-aligned-201": 4.65
        },
        "mrr": {
          "fixed-aligned-201": 9.48
        },
        "contextPrecision@10": {
          "fixed-aligned-201": 2.32
        },
        "recall@10": {
          "fixed-aligned-201": 4.65
        },
        "firstThreeHitsTokens": {
          "fixed-aligned-201": 5.5814
        }
      },
      "notes": [
        "这一组刻意「不」对齐块大小（差着约 -2 token），用来看「块更大 → 更容易覆盖答案」这个效应本身有多大"
      ]
    }
  ],
  "budgetSteps": [
    1000,
    2000,
    3000,
    4000,
    6000,
    8000
  ],
  "budgetLabel": [
    "fixed-aligned-202",
    "structural-nobc",
    "fixed-aligned-201",
    "structural",
    "fixed-size"
  ],
  "budgetRows": {
    "1000": {
      "fixed-aligned-202": {
        "budget": 1000,
        "recall": 0.7907,
        "mrr": 0.626,
        "avgTokensUsed": 884.6512,
        "avgHitCount": 4.1628
      },
      "structural-nobc": {
        "budget": 1000,
        "recall": 0.7209,
        "mrr": 0.5047,
        "avgTokensUsed": 857,
        "avgHitCount": 4.3023
      },
      "fixed-aligned-201": {
        "budget": 1000,
        "recall": 0.7674,
        "mrr": 0.6027,
        "avgTokensUsed": 875.1163,
        "avgHitCount": 4.186
      },
      "structural": {
        "budget": 1000,
        "recall": 0.7442,
        "mrr": 0.5302,
        "avgTokensUsed": 902.8837,
        "avgHitCount": 4.6512
      },
      "fixed-size": {
        "budget": 1000,
        "recall": 0.7674,
        "mrr": 0.6163,
        "avgTokensUsed": 861.2791,
        "avgHitCount": 3.5349
      }
    },
    "2000": {
      "fixed-aligned-202": {
        "budget": 2000,
        "recall": 0.9302,
        "mrr": 0.6469,
        "avgTokensUsed": 1902.0233,
        "avgHitCount": 8.8837
      },
      "structural-nobc": {
        "budget": 2000,
        "recall": 0.814,
        "mrr": 0.5215,
        "avgTokensUsed": 1811.1628,
        "avgHitCount": 8.4186
      },
      "fixed-aligned-201": {
        "budget": 2000,
        "recall": 0.8837,
        "mrr": 0.6192,
        "avgTokensUsed": 1903.7674,
        "avgHitCount": 9.0233
      },
      "structural": {
        "budget": 2000,
        "recall": 0.8372,
        "mrr": 0.5452,
        "avgTokensUsed": 1817.4884,
        "avgHitCount": 9.1628
      },
      "fixed-size": {
        "budget": 2000,
        "recall": 0.8605,
        "mrr": 0.6339,
        "avgTokensUsed": 1869.8372,
        "avgHitCount": 7.6279
      }
    },
    "3000": {
      "fixed-aligned-202": {
        "budget": 3000,
        "recall": 0.9302,
        "mrr": 0.6469,
        "avgTokensUsed": 2150.7209,
        "avgHitCount": 10
      },
      "structural-nobc": {
        "budget": 3000,
        "recall": 0.8372,
        "mrr": 0.5244,
        "avgTokensUsed": 2241.093,
        "avgHitCount": 9.9535
      },
      "fixed-aligned-201": {
        "budget": 3000,
        "recall": 0.8837,
        "mrr": 0.6192,
        "avgTokensUsed": 2118.8837,
        "avgHitCount": 10
      },
      "structural": {
        "budget": 3000,
        "recall": 0.8372,
        "mrr": 0.5452,
        "avgTokensUsed": 2016.3488,
        "avgHitCount": 10
      },
      "fixed-size": {
        "budget": 3000,
        "recall": 0.8605,
        "mrr": 0.6339,
        "avgTokensUsed": 2461.7674,
        "avgHitCount": 10
      }
    },
    "4000": {
      "fixed-aligned-202": {
        "budget": 4000,
        "recall": 0.9302,
        "mrr": 0.6469,
        "avgTokensUsed": 2150.7209,
        "avgHitCount": 10
      },
      "structural-nobc": {
        "budget": 4000,
        "recall": 0.8372,
        "mrr": 0.5244,
        "avgTokensUsed": 2256.0233,
        "avgHitCount": 10
      },
      "fixed-aligned-201": {
        "budget": 4000,
        "recall": 0.8837,
        "mrr": 0.6192,
        "avgTokensUsed": 2118.8837,
        "avgHitCount": 10
      },
      "structural": {
        "budget": 4000,
        "recall": 0.8372,
        "mrr": 0.5452,
        "avgTokensUsed": 2016.3488,
        "avgHitCount": 10
      },
      "fixed-size": {
        "budget": 4000,
        "recall": 0.8605,
        "mrr": 0.6339,
        "avgTokensUsed": 2461.7674,
        "avgHitCount": 10
      }
    },
    "6000": {
      "fixed-aligned-202": {
        "budget": 6000,
        "recall": 0.9302,
        "mrr": 0.6469,
        "avgTokensUsed": 2150.7209,
        "avgHitCount": 10
      },
      "structural-nobc": {
        "budget": 6000,
        "recall": 0.8372,
        "mrr": 0.5244,
        "avgTokensUsed": 2256.0233,
        "avgHitCount": 10
      },
      "fixed-aligned-201": {
        "budget": 6000,
        "recall": 0.8837,
        "mrr": 0.6192,
        "avgTokensUsed": 2118.8837,
        "avgHitCount": 10
      },
      "structural": {
        "budget": 6000,
        "recall": 0.8372,
        "mrr": 0.5452,
        "avgTokensUsed": 2016.3488,
        "avgHitCount": 10
      },
      "fixed-size": {
        "budget": 6000,
        "recall": 0.8605,
        "mrr": 0.6339,
        "avgTokensUsed": 2461.7674,
        "avgHitCount": 10
      }
    },
    "8000": {
      "fixed-aligned-202": {
        "budget": 8000,
        "recall": 0.9302,
        "mrr": 0.6469,
        "avgTokensUsed": 2150.7209,
        "avgHitCount": 10
      },
      "structural-nobc": {
        "budget": 8000,
        "recall": 0.8372,
        "mrr": 0.5244,
        "avgTokensUsed": 2256.0233,
        "avgHitCount": 10
      },
      "fixed-aligned-201": {
        "budget": 8000,
        "recall": 0.8837,
        "mrr": 0.6192,
        "avgTokensUsed": 2118.8837,
        "avgHitCount": 10
      },
      "structural": {
        "budget": 8000,
        "recall": 0.8372,
        "mrr": 0.5452,
        "avgTokensUsed": 2016.3488,
        "avgHitCount": 10
      },
      "fixed-size": {
        "budget": 8000,
        "recall": 0.8605,
        "mrr": 0.6339,
        "avgTokensUsed": 2461.7674,
        "avgHitCount": 10
      }
    }
  },
  "retrievalMethods": [
    "lexical-bm25",
    "vector-local",
    "hybrid-rrf"
  ],
  "embeddingModel": "local-hash-idf",
  "embeddingDimensions": 512,
  "vectorFingerprint": "local-hash-idf/512",
  "notes": [
    "「两个轴必须一起看」：轴一（块大小对齐）回答「切点选得好不好」，轴二（等 token 预算）回答「同样的钱谁找得回答案」。只报一个都会得出片面结论",
    "「检索方式也是一个实验维度」：同一组对齐对照在 [lexical-bm25, vector-local, hybrid-rrf] 下各比一次。分块策略的结论可能依赖用什么检索 —— 只报一种检索下的结论，等于把这个交叉因素当成了常量",
    "⚠ 「向量那一路不是语义嵌入，它是本机的离线哈希向量」（local-hash-idf，512 维）：本机实测 DeepSeek 没有 embedding 端点（/v1/embeddings 返回 404），且 pgvector 装不上（Docker 引擎 OsNotSupported、无本机 PG、无 MSVC 工具链）。它是「哈希技巧 + TF-IDF」，仍建立在「词面重合」上，所以「不能」用它得出「向量检索比 BM25 强」或「混合检索提升了多少」——换一个真 embedding 模型，那两路数字会完全不一样。它能支撑的只有「换了度量方式之后，分块策略的结论变了吗」这一个问题",
    "差值与颜色的方向一律是「右侧相对左侧」（百分点）。在一张有多个数字的对照表里，方向靠猜一定会猜错",
    "对齐后的实测平均块大小与目标差 0.41 token —— 「对齐」是近似的，这个差距是否足够小要由读者判断，不能由实现方默认成立",
    "「关掉面包屑的那一组才是在比「切点」」：结构分块开/关面包屑的实测规模是 200.6 token × 234 块 vs 202.4 token × 193 块（同一份语料、同一个 max-tokens=400）。两组「只有面包屑这一个开关不同」，所以它们的全部差异都来自「结构元数据有没有进块」这件事。⚠ 注意带面包屑那一组的平均块反而更小、块数更多（234 vs 193）——这是实测结果，不是预期方向；本报告只对齐块大小，「不对这个现象作解释」，要弄清它得单独查 StructuralChunker 的装填逻辑",
    "面包屑的取舍本身是一个「独立实验」（它是特征，不是切点）：真实系统里通常值得开（用户提问常直接用标题词，而正文可能一次都没写），这里关掉只是为了把「切点」这一个变量隔离出来",
    "等预算表的档位是 [1000, 2000, 3000, 4000, 6000, 8000]。预算口径是「装到下一个块会超预算为止」，第一条无条件放行（现实中不会因为单个块太大就一条都不给）——所以要同时看 avgTokensUsed 与 avgHitCount：预算没装满就是「块太大装不下」的直接证据",
    "本阶段检索的「词法那一路」为 BM25：它只认词面。所以对齐之后若结构分块仍然略低，结论是「在词法检索下，按结构切没有占到便宜」——「不能」推出「语义分块更差」（换真语义嵌入后词法这个约束就没了，结论可能反过来）"
  ]
}
