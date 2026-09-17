/**
 * 块大小对齐实验（`/api/diag/rag/align`）的**假数据**。
 *
 * ⚠️ 这个文件由 `scripts/gen-align-mock.mjs` 从真后端的一次实测响应生成，
 * **不要手改数字** —— 手改就会与"界面上的解读文字"脱钩，而自检断言不了
 * "文字和数字是否一致"（rag-routes.mjs 开头那段说的就是这个坑）。
 * 要更新就重新跑一次真后端 + 生成脚本。
 *
 * 数值来源：真启动的 Spring Boot 后端，GET /api/diag/rag/align
 *   43 条用例 / 4 份文档 / 516 块 / 三路检索 / 六档预算 / HTTP 200 / 185KB / 4.95s
 *
 * 它承载的结论（阶段二「块大小对齐实验」，完整说明见 `docs/项目功能点与代码索引.md` 的 3.5 节）：
 *   块大小对齐之后（202.7 vs 203.1 token，差 0.39），**固定字数硬切全面占优** ——
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
  "alignTargetTokens": 203.1295,
  "sweepRange": [
    325,
    541
  ],
  "sweep": [
    {
      "fixedSize": 325,
      "chunkCount": 241,
      "avgChunkTokens": 154.1494,
      "totalTokens": 37150,
      "duplicationRatio": 0.146,
      "gapToTarget": -48.9801
    },
    {
      "fixedSize": 352,
      "chunkCount": 222,
      "avgChunkTokens": 167.1216,
      "totalTokens": 37101,
      "duplicationRatio": 0.1445,
      "gapToTarget": -36.0079
    },
    {
      "fixedSize": 379,
      "chunkCount": 207,
      "avgChunkTokens": 178.6715,
      "totalTokens": 36985,
      "duplicationRatio": 0.1409,
      "gapToTarget": -24.458
    },
    {
      "fixedSize": 406,
      "chunkCount": 193,
      "avgChunkTokens": 191.0777,
      "totalTokens": 36878,
      "duplicationRatio": 0.1376,
      "gapToTarget": -12.0518
    },
    {
      "fixedSize": 433,
      "chunkCount": 181,
      "avgChunkTokens": 203.6796,
      "totalTokens": 36866,
      "duplicationRatio": 0.1372,
      "gapToTarget": 0.5501
    },
    {
      "fixedSize": 460,
      "chunkCount": 171,
      "avgChunkTokens": 214.8421,
      "totalTokens": 36738,
      "duplicationRatio": 0.1333,
      "gapToTarget": 11.7126
    },
    {
      "fixedSize": 487,
      "chunkCount": 162,
      "avgChunkTokens": 226.3086,
      "totalTokens": 36662,
      "duplicationRatio": 0.1309,
      "gapToTarget": 23.1791
    },
    {
      "fixedSize": 514,
      "chunkCount": 152,
      "avgChunkTokens": 240.4934,
      "totalTokens": 36555,
      "duplicationRatio": 0.1276,
      "gapToTarget": 37.3639
    },
    {
      "fixedSize": 541,
      "chunkCount": 146,
      "avgChunkTokens": 251.4041,
      "totalTokens": 36705,
      "duplicationRatio": 0.1322,
      "gapToTarget": 48.2746
    }
  ],
  "alignedFixedSize": 431,
  "alignedMeasuredAvg": 202.7418,
  "alignedGapTokens": -0.3877,
  "refinementProbes": 14,
  "pairs": [
    {
      "fieldDescription": "平均块大小 == 203.1 token（结构分块关掉面包屑，正文也对齐） ｜ 检索=lexical-bm25 ｜ 左=fixed 431字/43重叠 → 实测平均 202.7 token ｜ 右=structural max-tokens=400 无面包屑",
      "dimension": "平均块大小",
      "reports": [
        {
          "strategy": "fixed-aligned-203",
          "chunkCount": 182,
          "avgChunkTokens": 202.7418,
          "maxChunkTokens": 281,
          "metrics": {
            "strategy": "fixed-aligned-203",
            "caseCount": 43,
            "chunkCount": 182,
            "avgChunkTokens": 202.7418,
            "recallAt": {
              "1": 0.4884,
              "3": 0.7442,
              "5": 0.7907,
              "10": 0.8605
            },
            "mrr": 0.6347,
            "contextPrecisionAt": {
              "1": 0.4884,
              "3": 0.3178,
              "5": 0.2651,
              "10": 0.1814
            },
            "firstThreeHitsTokens": 644.4884,
            "topKTokens": 2124.7674,
            "budgetAlignment": {
              "1000": {
                "budget": 1000,
                "recall": 0.7907,
                "mrr": 0.624,
                "avgTokensUsed": 891.6977,
                "avgHitCount": 4.2326
              },
              "2000": {
                "budget": 2000,
                "recall": 0.8605,
                "mrr": 0.6347,
                "avgTokensUsed": 1889.6279,
                "avgHitCount": 8.9535
              },
              "3000": {
                "budget": 3000,
                "recall": 0.8605,
                "mrr": 0.6347,
                "avgTokensUsed": 2124.7674,
                "avgHitCount": 10
              },
              "4000": {
                "budget": 4000,
                "recall": 0.8605,
                "mrr": 0.6347,
                "avgTokensUsed": 2124.7674,
                "avgHitCount": 10
              },
              "6000": {
                "budget": 6000,
                "recall": 0.8605,
                "mrr": 0.6347,
                "avgTokensUsed": 2124.7674,
                "avgHitCount": 10
              },
              "8000": {
                "budget": 8000,
                "recall": 0.8605,
                "mrr": 0.6347,
                "avgTokensUsed": 2124.7674,
                "avgHitCount": 10
              }
            },
            "misses": [],
            "perTag": {}
          },
          "tokenCost": {
            "totalTokens": 36899,
            "duplicationRatio": 0.1382,
            "minTokens": 39,
            "undersizedChunks": 1
          }
        },
        {
          "strategy": "structural-nobc",
          "chunkCount": 193,
          "avgChunkTokens": 203.1295,
          "maxChunkTokens": 389,
          "metrics": {
            "strategy": "structural-nobc",
            "caseCount": 43,
            "chunkCount": 193,
            "avgChunkTokens": 203.1295,
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
              "5": 0.2279,
              "10": 0.1721
            },
            "firstThreeHitsTokens": 628.6512,
            "topKTokens": 2246.5814,
            "budgetAlignment": {
              "1000": {
                "budget": 1000,
                "recall": 0.7209,
                "mrr": 0.5047,
                "avgTokensUsed": 859.2326,
                "avgHitCount": 4.3023
              },
              "2000": {
                "budget": 2000,
                "recall": 0.814,
                "mrr": 0.5215,
                "avgTokensUsed": 1803.6047,
                "avgHitCount": 8.4186
              },
              "3000": {
                "budget": 3000,
                "recall": 0.8372,
                "mrr": 0.5244,
                "avgTokensUsed": 2231.6512,
                "avgHitCount": 9.9535
              },
              "4000": {
                "budget": 4000,
                "recall": 0.8372,
                "mrr": 0.5244,
                "avgTokensUsed": 2246.5814,
                "avgHitCount": 10
              },
              "6000": {
                "budget": 6000,
                "recall": 0.8372,
                "mrr": 0.5244,
                "avgTokensUsed": 2246.5814,
                "avgHitCount": 10
              },
              "8000": {
                "budget": 8000,
                "recall": 0.8372,
                "mrr": 0.5244,
                "avgTokensUsed": 2246.5814,
                "avgHitCount": 10
              }
            },
            "misses": [],
            "perTag": {}
          },
          "tokenCost": {
            "totalTokens": 39204,
            "duplicationRatio": 0.2093,
            "minTokens": 11,
            "undersizedChunks": 42
          }
        }
      ],
      "deltas": {
        "contextPrecision@10": {
          "structural-nobc": -0.93
        },
        "mrr": {
          "structural-nobc": -11.03
        },
        "contextPrecision@5": {
          "structural-nobc": -3.72
        },
        "recall@10": {
          "structural-nobc": -2.33
        },
        "firstThreeHitsTokens": {
          "structural-nobc": -15.8372
        },
        "avgChunkTokens": {
          "structural-nobc": 0.3877
        },
        "recall@1": {
          "structural-nobc": -9.31
        },
        "duplicationRatio": {
          "structural-nobc": 0.0711
        },
        "recall@3": {
          "structural-nobc": -13.95
        },
        "recall@5": {
          "structural-nobc": -4.65
        },
        "chunkCount": {
          "structural-nobc": 11
        },
        "contextPrecision@1": {
          "structural-nobc": -9.31
        },
        "topKTokens": {
          "structural-nobc": 121.814
        },
        "contextPrecision@3": {
          "structural-nobc": -6.2
        }
      },
      "notes": [
        "这是「最干净的一组」：块大小对齐、正文也对齐（关掉面包屑），于是唯一的差异只剩「切点选在哪」",
        "对照组的块大小是按实测收敛出来的，与目标差 -0.39 token —— 不是完全相等"
      ]
    },
    {
      "fieldDescription": "平均块大小 == 203.1 token（结构分块关掉面包屑，正文也对齐） ｜ 检索=vector-local ｜ 左=fixed 431字/43重叠 → 实测平均 202.7 token ｜ 右=structural max-tokens=400 无面包屑",
      "dimension": "平均块大小",
      "reports": [
        {
          "strategy": "fixed-aligned-203",
          "chunkCount": 182,
          "avgChunkTokens": 202.7418,
          "maxChunkTokens": 281,
          "metrics": {
            "strategy": "fixed-aligned-203",
            "caseCount": 43,
            "chunkCount": 182,
            "avgChunkTokens": 202.7418,
            "recallAt": {
              "1": 0.2326,
              "3": 0.3721,
              "5": 0.4651,
              "10": 0.6047
            },
            "mrr": 0.3379,
            "contextPrecisionAt": {
              "1": 0.2326,
              "3": 0.1473,
              "5": 0.1302,
              "10": 0.1047
            },
            "firstThreeHitsTokens": 626.6744,
            "topKTokens": 2087.4651,
            "budgetAlignment": {},
            "misses": [],
            "perTag": {}
          },
          "tokenCost": {
            "totalTokens": 36899,
            "duplicationRatio": 0.1382,
            "minTokens": 39,
            "undersizedChunks": 1
          }
        },
        {
          "strategy": "structural-nobc",
          "chunkCount": 193,
          "avgChunkTokens": 203.1295,
          "maxChunkTokens": 389,
          "metrics": {
            "strategy": "structural-nobc",
            "caseCount": 43,
            "chunkCount": 193,
            "avgChunkTokens": 203.1295,
            "recallAt": {
              "1": 0.2326,
              "3": 0.3256,
              "5": 0.3488,
              "10": 0.4419
            },
            "mrr": 0.2836,
            "contextPrecisionAt": {
              "1": 0.2326,
              "3": 0.124,
              "5": 0.0884,
              "10": 0.0721
            },
            "firstThreeHitsTokens": 607.5349,
            "topKTokens": 2152.5116,
            "budgetAlignment": {},
            "misses": [],
            "perTag": {}
          },
          "tokenCost": {
            "totalTokens": 39204,
            "duplicationRatio": 0.2093,
            "minTokens": 11,
            "undersizedChunks": 42
          }
        }
      ],
      "deltas": {
        "contextPrecision@10": {
          "structural-nobc": -3.26
        },
        "mrr": {
          "structural-nobc": -5.43
        },
        "contextPrecision@5": {
          "structural-nobc": -4.18
        },
        "recall@10": {
          "structural-nobc": -16.28
        },
        "firstThreeHitsTokens": {
          "structural-nobc": -19.1395
        },
        "avgChunkTokens": {
          "structural-nobc": 0.3877
        },
        "recall@1": {
          "structural-nobc": 0
        },
        "duplicationRatio": {
          "structural-nobc": 0.0711
        },
        "recall@3": {
          "structural-nobc": -4.65
        },
        "recall@5": {
          "structural-nobc": -11.63
        },
        "chunkCount": {
          "structural-nobc": 11
        },
        "contextPrecision@1": {
          "structural-nobc": 0
        },
        "topKTokens": {
          "structural-nobc": 65.0465
        },
        "contextPrecision@3": {
          "structural-nobc": -2.33
        }
      },
      "notes": [
        "这是「最干净的一组」：块大小对齐、正文也对齐（关掉面包屑），于是唯一的差异只剩「切点选在哪」",
        "对照组的块大小是按实测收敛出来的，与目标差 -0.39 token —— 不是完全相等"
      ]
    },
    {
      "fieldDescription": "平均块大小 == 203.1 token（结构分块关掉面包屑，正文也对齐） ｜ 检索=hybrid-rrf ｜ 左=fixed 431字/43重叠 → 实测平均 202.7 token ｜ 右=structural max-tokens=400 无面包屑",
      "dimension": "平均块大小",
      "reports": [
        {
          "strategy": "fixed-aligned-203",
          "chunkCount": 182,
          "avgChunkTokens": 202.7418,
          "maxChunkTokens": 281,
          "metrics": {
            "strategy": "fixed-aligned-203",
            "caseCount": 43,
            "chunkCount": 182,
            "avgChunkTokens": 202.7418,
            "recallAt": {
              "1": 0.3953,
              "3": 0.5349,
              "5": 0.7209,
              "10": 0.8605
            },
            "mrr": 0.5243,
            "contextPrecisionAt": {
              "1": 0.3953,
              "3": 0.2326,
              "5": 0.2279,
              "10": 0.1767
            },
            "firstThreeHitsTokens": 629.0698,
            "topKTokens": 2085.8372,
            "budgetAlignment": {},
            "misses": [],
            "perTag": {}
          },
          "tokenCost": {
            "totalTokens": 36899,
            "duplicationRatio": 0.1382,
            "minTokens": 39,
            "undersizedChunks": 1
          }
        },
        {
          "strategy": "structural-nobc",
          "chunkCount": 193,
          "avgChunkTokens": 203.1295,
          "maxChunkTokens": 389,
          "metrics": {
            "strategy": "structural-nobc",
            "caseCount": 43,
            "chunkCount": 193,
            "avgChunkTokens": 203.1295,
            "recallAt": {
              "1": 0.3256,
              "3": 0.4884,
              "5": 0.6744,
              "10": 0.814
            },
            "mrr": 0.4605,
            "contextPrecisionAt": {
              "1": 0.3256,
              "3": 0.2016,
              "5": 0.186,
              "10": 0.1372
            },
            "firstThreeHitsTokens": 628.7209,
            "topKTokens": 2098.0233,
            "budgetAlignment": {},
            "misses": [],
            "perTag": {}
          },
          "tokenCost": {
            "totalTokens": 39204,
            "duplicationRatio": 0.2093,
            "minTokens": 11,
            "undersizedChunks": 42
          }
        }
      ],
      "deltas": {
        "contextPrecision@10": {
          "structural-nobc": -3.95
        },
        "mrr": {
          "structural-nobc": -6.38
        },
        "contextPrecision@5": {
          "structural-nobc": -4.19
        },
        "recall@10": {
          "structural-nobc": -4.65
        },
        "firstThreeHitsTokens": {
          "structural-nobc": -0.3489
        },
        "avgChunkTokens": {
          "structural-nobc": 0.3877
        },
        "recall@1": {
          "structural-nobc": -6.97
        },
        "duplicationRatio": {
          "structural-nobc": 0.0711
        },
        "recall@3": {
          "structural-nobc": -4.65
        },
        "recall@5": {
          "structural-nobc": -4.65
        },
        "chunkCount": {
          "structural-nobc": 11
        },
        "contextPrecision@1": {
          "structural-nobc": -6.97
        },
        "topKTokens": {
          "structural-nobc": 12.1861
        },
        "contextPrecision@3": {
          "structural-nobc": -3.1
        }
      },
      "notes": [
        "这是「最干净的一组」：块大小对齐、正文也对齐（关掉面包屑），于是唯一的差异只剩「切点选在哪」",
        "对照组的块大小是按实测收敛出来的，与目标差 -0.39 token —— 不是完全相等"
      ]
    },
    {
      "fieldDescription": "平均块大小 == 200.9 token（结构分块默认带面包屑） ｜ 检索=lexical-bm25 ｜ 左=fixed 429字/42重叠 → 实测平均 201.3 token ｜ 右=structural max-tokens=400 +面包屑",
      "dimension": "平均块大小",
      "reports": [
        {
          "strategy": "fixed-aligned-201",
          "chunkCount": 183,
          "avgChunkTokens": 201.306,
          "maxChunkTokens": 282,
          "metrics": {
            "strategy": "fixed-aligned-201",
            "caseCount": 43,
            "chunkCount": 183,
            "avgChunkTokens": 201.306,
            "recallAt": {
              "1": 0.5349,
              "3": 0.7209,
              "5": 0.7442,
              "10": 0.8605
            },
            "mrr": 0.6457,
            "contextPrecisionAt": {
              "1": 0.5349,
              "3": 0.3411,
              "5": 0.2558,
              "10": 0.1884
            },
            "firstThreeHitsTokens": 636.9767,
            "topKTokens": 2113.6279,
            "budgetAlignment": {
              "1000": {
                "budget": 1000,
                "recall": 0.7209,
                "mrr": 0.624,
                "avgTokensUsed": 886.093,
                "avgHitCount": 4.2093
              },
              "2000": {
                "budget": 2000,
                "recall": 0.8605,
                "mrr": 0.6457,
                "avgTokensUsed": 1893.4186,
                "avgHitCount": 8.9767
              },
              "3000": {
                "budget": 3000,
                "recall": 0.8605,
                "mrr": 0.6457,
                "avgTokensUsed": 2113.6279,
                "avgHitCount": 10
              },
              "4000": {
                "budget": 4000,
                "recall": 0.8605,
                "mrr": 0.6457,
                "avgTokensUsed": 2113.6279,
                "avgHitCount": 10
              },
              "6000": {
                "budget": 6000,
                "recall": 0.8605,
                "mrr": 0.6457,
                "avgTokensUsed": 2113.6279,
                "avgHitCount": 10
              },
              "8000": {
                "budget": 8000,
                "recall": 0.8605,
                "mrr": 0.6457,
                "avgTokensUsed": 2113.6279,
                "avgHitCount": 10
              }
            },
            "misses": [],
            "perTag": {}
          },
          "tokenCost": {
            "totalTokens": 36839,
            "duplicationRatio": 0.1364,
            "minTokens": 33,
            "undersizedChunks": 1
          }
        },
        {
          "strategy": "structural",
          "chunkCount": 235,
          "avgChunkTokens": 200.8553,
          "maxChunkTokens": 390,
          "metrics": {
            "strategy": "structural",
            "caseCount": 43,
            "chunkCount": 235,
            "avgChunkTokens": 200.8553,
            "recallAt": {
              "1": 0.4186,
              "3": 0.6047,
              "5": 0.7674,
              "10": 0.8372
            },
            "mrr": 0.5452,
            "contextPrecisionAt": {
              "1": 0.4186,
              "3": 0.2868,
              "5": 0.2512,
              "10": 0.193
            },
            "firstThreeHitsTokens": 590.2326,
            "topKTokens": 2013.4419,
            "budgetAlignment": {
              "1000": {
                "budget": 1000,
                "recall": 0.7442,
                "mrr": 0.5302,
                "avgTokensUsed": 903.2093,
                "avgHitCount": 4.6279
              },
              "2000": {
                "budget": 2000,
                "recall": 0.8372,
                "mrr": 0.5452,
                "avgTokensUsed": 1816.4186,
                "avgHitCount": 9.1628
              },
              "3000": {
                "budget": 3000,
                "recall": 0.8372,
                "mrr": 0.5452,
                "avgTokensUsed": 2013.4419,
                "avgHitCount": 10
              },
              "4000": {
                "budget": 4000,
                "recall": 0.8372,
                "mrr": 0.5452,
                "avgTokensUsed": 2013.4419,
                "avgHitCount": 10
              },
              "6000": {
                "budget": 6000,
                "recall": 0.8372,
                "mrr": 0.5452,
                "avgTokensUsed": 2013.4419,
                "avgHitCount": 10
              },
              "8000": {
                "budget": 8000,
                "recall": 0.8372,
                "mrr": 0.5452,
                "avgTokensUsed": 2013.4419,
                "avgHitCount": 10
              }
            },
            "misses": [],
            "perTag": {}
          },
          "tokenCost": {
            "totalTokens": 47201,
            "duplicationRatio": 0.456,
            "minTokens": 19,
            "undersizedChunks": 16
          }
        }
      ],
      "deltas": {
        "contextPrecision@10": {
          "structural": 0.46
        },
        "mrr": {
          "structural": -10.05
        },
        "contextPrecision@5": {
          "structural": -0.46
        },
        "recall@10": {
          "structural": -2.33
        },
        "firstThreeHitsTokens": {
          "structural": -46.7441
        },
        "avgChunkTokens": {
          "structural": -0.4507
        },
        "recall@1": {
          "structural": -11.63
        },
        "duplicationRatio": {
          "structural": 0.3196
        },
        "recall@3": {
          "structural": -11.62
        },
        "recall@5": {
          "structural": 2.32
        },
        "chunkCount": {
          "structural": 52
        },
        "contextPrecision@1": {
          "structural": -11.63
        },
        "topKTokens": {
          "structural": -100.186
        },
        "contextPrecision@3": {
          "structural": -5.43
        }
      },
      "notes": [
        "这一组对齐的是「渲染后」的块大小：结构分块的正文仍然比对照组小（差的就是每块拼一遍的标题路径），所以它不能单独用来判断「切点」的优劣 ——判断切点请用关掉面包屑的那一组"
      ]
    },
    {
      "fieldDescription": "平均块大小 == 200.9 token（结构分块默认带面包屑） ｜ 检索=vector-local ｜ 左=fixed 429字/42重叠 → 实测平均 201.3 token ｜ 右=structural max-tokens=400 +面包屑",
      "dimension": "平均块大小",
      "reports": [
        {
          "strategy": "fixed-aligned-201",
          "chunkCount": 183,
          "avgChunkTokens": 201.306,
          "maxChunkTokens": 282,
          "metrics": {
            "strategy": "fixed-aligned-201",
            "caseCount": 43,
            "chunkCount": 183,
            "avgChunkTokens": 201.306,
            "recallAt": {
              "1": 0.3023,
              "3": 0.4419,
              "5": 0.4651,
              "10": 0.6047
            },
            "mrr": 0.3873,
            "contextPrecisionAt": {
              "1": 0.3023,
              "3": 0.1783,
              "5": 0.1349,
              "10": 0.1116
            },
            "firstThreeHitsTokens": 618.7442,
            "topKTokens": 2052.093,
            "budgetAlignment": {},
            "misses": [],
            "perTag": {}
          },
          "tokenCost": {
            "totalTokens": 36839,
            "duplicationRatio": 0.1364,
            "minTokens": 33,
            "undersizedChunks": 1
          }
        },
        {
          "strategy": "structural",
          "chunkCount": 235,
          "avgChunkTokens": 200.8553,
          "maxChunkTokens": 390,
          "metrics": {
            "strategy": "structural",
            "caseCount": 43,
            "chunkCount": 235,
            "avgChunkTokens": 200.8553,
            "recallAt": {
              "1": 0.2558,
              "3": 0.4884,
              "5": 0.5814,
              "10": 0.6744
            },
            "mrr": 0.3969,
            "contextPrecisionAt": {
              "1": 0.2558,
              "3": 0.186,
              "5": 0.1442,
              "10": 0.1116
            },
            "firstThreeHitsTokens": 620.4186,
            "topKTokens": 2048.093,
            "budgetAlignment": {},
            "misses": [],
            "perTag": {}
          },
          "tokenCost": {
            "totalTokens": 47201,
            "duplicationRatio": 0.456,
            "minTokens": 19,
            "undersizedChunks": 16
          }
        }
      ],
      "deltas": {
        "contextPrecision@10": {
          "structural": 0
        },
        "mrr": {
          "structural": 0.96
        },
        "contextPrecision@5": {
          "structural": 0.93
        },
        "recall@10": {
          "structural": 6.97
        },
        "firstThreeHitsTokens": {
          "structural": 1.6744
        },
        "avgChunkTokens": {
          "structural": -0.4507
        },
        "recall@1": {
          "structural": -4.65
        },
        "duplicationRatio": {
          "structural": 0.3196
        },
        "recall@3": {
          "structural": 4.65
        },
        "recall@5": {
          "structural": 11.63
        },
        "chunkCount": {
          "structural": 52
        },
        "contextPrecision@1": {
          "structural": -4.65
        },
        "topKTokens": {
          "structural": -4
        },
        "contextPrecision@3": {
          "structural": 0.77
        }
      },
      "notes": [
        "这一组对齐的是「渲染后」的块大小：结构分块的正文仍然比对照组小（差的就是每块拼一遍的标题路径），所以它不能单独用来判断「切点」的优劣 ——判断切点请用关掉面包屑的那一组"
      ]
    },
    {
      "fieldDescription": "平均块大小 == 200.9 token（结构分块默认带面包屑） ｜ 检索=hybrid-rrf ｜ 左=fixed 429字/42重叠 → 实测平均 201.3 token ｜ 右=structural max-tokens=400 +面包屑",
      "dimension": "平均块大小",
      "reports": [
        {
          "strategy": "fixed-aligned-201",
          "chunkCount": 183,
          "avgChunkTokens": 201.306,
          "maxChunkTokens": 282,
          "metrics": {
            "strategy": "fixed-aligned-201",
            "caseCount": 43,
            "chunkCount": 183,
            "avgChunkTokens": 201.306,
            "recallAt": {
              "1": 0.4651,
              "3": 0.5581,
              "5": 0.7442,
              "10": 0.7907
            },
            "mrr": 0.5479,
            "contextPrecisionAt": {
              "1": 0.4651,
              "3": 0.2713,
              "5": 0.2512,
              "10": 0.1721
            },
            "firstThreeHitsTokens": 625.4884,
            "topKTokens": 2081.4186,
            "budgetAlignment": {},
            "misses": [],
            "perTag": {}
          },
          "tokenCost": {
            "totalTokens": 36839,
            "duplicationRatio": 0.1364,
            "minTokens": 33,
            "undersizedChunks": 1
          }
        },
        {
          "strategy": "structural",
          "chunkCount": 235,
          "avgChunkTokens": 200.8553,
          "maxChunkTokens": 390,
          "metrics": {
            "strategy": "structural",
            "caseCount": 43,
            "chunkCount": 235,
            "avgChunkTokens": 200.8553,
            "recallAt": {
              "1": 0.4651,
              "3": 0.6512,
              "5": 0.7674,
              "10": 0.8837
            },
            "mrr": 0.5879,
            "contextPrecisionAt": {
              "1": 0.4651,
              "3": 0.2791,
              "5": 0.2233,
              "10": 0.1674
            },
            "firstThreeHitsTokens": 633.4884,
            "topKTokens": 2039.0233,
            "budgetAlignment": {},
            "misses": [],
            "perTag": {}
          },
          "tokenCost": {
            "totalTokens": 47201,
            "duplicationRatio": 0.456,
            "minTokens": 19,
            "undersizedChunks": 16
          }
        }
      ],
      "deltas": {
        "contextPrecision@10": {
          "structural": -0.47
        },
        "mrr": {
          "structural": 4
        },
        "contextPrecision@5": {
          "structural": -2.79
        },
        "recall@10": {
          "structural": 9.3
        },
        "firstThreeHitsTokens": {
          "structural": 8
        },
        "avgChunkTokens": {
          "structural": -0.4507
        },
        "recall@1": {
          "structural": 0
        },
        "duplicationRatio": {
          "structural": 0.3196
        },
        "recall@3": {
          "structural": 9.31
        },
        "recall@5": {
          "structural": 2.32
        },
        "chunkCount": {
          "structural": 52
        },
        "contextPrecision@1": {
          "structural": 0
        },
        "topKTokens": {
          "structural": -42.3953
        },
        "contextPrecision@3": {
          "structural": 0.78
        }
      },
      "notes": [
        "这一组对齐的是「渲染后」的块大小：结构分块的正文仍然比对照组小（差的就是每块拼一遍的标题路径），所以它不能单独用来判断「切点」的优劣 ——判断切点请用关掉面包屑的那一组"
      ]
    },
    {
      "fieldDescription": "块更大的效应有多大（结构分块无面包屑 vs 与默认组同大小的硬切） ｜ 检索=lexical-bm25 ｜ 左=structural max-tokens=400 无面包屑 ｜ 右=fixed 429字/42重叠 → 实测平均 201.3 token",
      "dimension": "等 token 预算",
      "reports": [
        {
          "strategy": "structural-nobc",
          "chunkCount": 193,
          "avgChunkTokens": 203.1295,
          "maxChunkTokens": 389,
          "metrics": {
            "strategy": "structural-nobc",
            "caseCount": 43,
            "chunkCount": 193,
            "avgChunkTokens": 203.1295,
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
              "5": 0.2279,
              "10": 0.1721
            },
            "firstThreeHitsTokens": 628.6512,
            "topKTokens": 2246.5814,
            "budgetAlignment": {
              "1000": {
                "budget": 1000,
                "recall": 0.7209,
                "mrr": 0.5047,
                "avgTokensUsed": 859.2326,
                "avgHitCount": 4.3023
              },
              "2000": {
                "budget": 2000,
                "recall": 0.814,
                "mrr": 0.5215,
                "avgTokensUsed": 1803.6047,
                "avgHitCount": 8.4186
              },
              "3000": {
                "budget": 3000,
                "recall": 0.8372,
                "mrr": 0.5244,
                "avgTokensUsed": 2231.6512,
                "avgHitCount": 9.9535
              },
              "4000": {
                "budget": 4000,
                "recall": 0.8372,
                "mrr": 0.5244,
                "avgTokensUsed": 2246.5814,
                "avgHitCount": 10
              },
              "6000": {
                "budget": 6000,
                "recall": 0.8372,
                "mrr": 0.5244,
                "avgTokensUsed": 2246.5814,
                "avgHitCount": 10
              },
              "8000": {
                "budget": 8000,
                "recall": 0.8372,
                "mrr": 0.5244,
                "avgTokensUsed": 2246.5814,
                "avgHitCount": 10
              }
            },
            "misses": [],
            "perTag": {}
          },
          "tokenCost": {
            "totalTokens": 39204,
            "duplicationRatio": 0.2093,
            "minTokens": 11,
            "undersizedChunks": 42
          }
        },
        {
          "strategy": "fixed-aligned-201",
          "chunkCount": 183,
          "avgChunkTokens": 201.306,
          "maxChunkTokens": 282,
          "metrics": {
            "strategy": "fixed-aligned-201",
            "caseCount": 43,
            "chunkCount": 183,
            "avgChunkTokens": 201.306,
            "recallAt": {
              "1": 0.5349,
              "3": 0.7209,
              "5": 0.7442,
              "10": 0.8605
            },
            "mrr": 0.6457,
            "contextPrecisionAt": {
              "1": 0.5349,
              "3": 0.3411,
              "5": 0.2558,
              "10": 0.1884
            },
            "firstThreeHitsTokens": 636.9767,
            "topKTokens": 2113.6279,
            "budgetAlignment": {
              "1000": {
                "budget": 1000,
                "recall": 0.7209,
                "mrr": 0.624,
                "avgTokensUsed": 886.093,
                "avgHitCount": 4.2093
              },
              "2000": {
                "budget": 2000,
                "recall": 0.8605,
                "mrr": 0.6457,
                "avgTokensUsed": 1893.4186,
                "avgHitCount": 8.9767
              },
              "3000": {
                "budget": 3000,
                "recall": 0.8605,
                "mrr": 0.6457,
                "avgTokensUsed": 2113.6279,
                "avgHitCount": 10
              },
              "4000": {
                "budget": 4000,
                "recall": 0.8605,
                "mrr": 0.6457,
                "avgTokensUsed": 2113.6279,
                "avgHitCount": 10
              },
              "6000": {
                "budget": 6000,
                "recall": 0.8605,
                "mrr": 0.6457,
                "avgTokensUsed": 2113.6279,
                "avgHitCount": 10
              },
              "8000": {
                "budget": 8000,
                "recall": 0.8605,
                "mrr": 0.6457,
                "avgTokensUsed": 2113.6279,
                "avgHitCount": 10
              }
            },
            "misses": [],
            "perTag": {}
          },
          "tokenCost": {
            "totalTokens": 36839,
            "duplicationRatio": 0.1364,
            "minTokens": 33,
            "undersizedChunks": 1
          }
        }
      ],
      "deltas": {
        "contextPrecision@10": {
          "fixed-aligned-201": 1.63
        },
        "mrr": {
          "fixed-aligned-201": 12.13
        },
        "contextPrecision@5": {
          "fixed-aligned-201": 2.79
        },
        "recall@10": {
          "fixed-aligned-201": 2.33
        },
        "firstThreeHitsTokens": {
          "fixed-aligned-201": 8.3255
        },
        "avgChunkTokens": {
          "fixed-aligned-201": -1.8235
        },
        "recall@1": {
          "fixed-aligned-201": 13.96
        },
        "duplicationRatio": {
          "fixed-aligned-201": -0.0729
        },
        "recall@3": {
          "fixed-aligned-201": 11.62
        },
        "recall@5": {
          "fixed-aligned-201": 0
        },
        "chunkCount": {
          "fixed-aligned-201": -10
        },
        "contextPrecision@1": {
          "fixed-aligned-201": 13.96
        },
        "topKTokens": {
          "fixed-aligned-201": -132.9535
        },
        "contextPrecision@3": {
          "fixed-aligned-201": 8.53
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
    "fixed-aligned-203",
    "structural-nobc",
    "fixed-aligned-201",
    "structural",
    "fixed-size"
  ],
  "budgetRows": {
    "1000": {
      "fixed-aligned-203": {
        "budget": 1000,
        "recall": 0.7907,
        "mrr": 0.624,
        "avgTokensUsed": 891.6977,
        "avgHitCount": 4.2326
      },
      "structural-nobc": {
        "budget": 1000,
        "recall": 0.7209,
        "mrr": 0.5047,
        "avgTokensUsed": 859.2326,
        "avgHitCount": 4.3023
      },
      "fixed-aligned-201": {
        "budget": 1000,
        "recall": 0.7209,
        "mrr": 0.624,
        "avgTokensUsed": 886.093,
        "avgHitCount": 4.2093
      },
      "structural": {
        "budget": 1000,
        "recall": 0.7442,
        "mrr": 0.5302,
        "avgTokensUsed": 903.2093,
        "avgHitCount": 4.6279
      },
      "fixed-size": {
        "budget": 1000,
        "recall": 0.7674,
        "mrr": 0.6008,
        "avgTokensUsed": 858.6512,
        "avgHitCount": 3.5349
      }
    },
    "2000": {
      "fixed-aligned-203": {
        "budget": 2000,
        "recall": 0.8605,
        "mrr": 0.6347,
        "avgTokensUsed": 1889.6279,
        "avgHitCount": 8.9535
      },
      "structural-nobc": {
        "budget": 2000,
        "recall": 0.814,
        "mrr": 0.5215,
        "avgTokensUsed": 1803.6047,
        "avgHitCount": 8.4186
      },
      "fixed-aligned-201": {
        "budget": 2000,
        "recall": 0.8605,
        "mrr": 0.6457,
        "avgTokensUsed": 1893.4186,
        "avgHitCount": 8.9767
      },
      "structural": {
        "budget": 2000,
        "recall": 0.8372,
        "mrr": 0.5452,
        "avgTokensUsed": 1816.4186,
        "avgHitCount": 9.1628
      },
      "fixed-size": {
        "budget": 2000,
        "recall": 0.8605,
        "mrr": 0.6173,
        "avgTokensUsed": 1868.1628,
        "avgHitCount": 7.6512
      }
    },
    "3000": {
      "fixed-aligned-203": {
        "budget": 3000,
        "recall": 0.8605,
        "mrr": 0.6347,
        "avgTokensUsed": 2124.7674,
        "avgHitCount": 10
      },
      "structural-nobc": {
        "budget": 3000,
        "recall": 0.8372,
        "mrr": 0.5244,
        "avgTokensUsed": 2231.6512,
        "avgHitCount": 9.9535
      },
      "fixed-aligned-201": {
        "budget": 3000,
        "recall": 0.8605,
        "mrr": 0.6457,
        "avgTokensUsed": 2113.6279,
        "avgHitCount": 10
      },
      "structural": {
        "budget": 3000,
        "recall": 0.8372,
        "mrr": 0.5452,
        "avgTokensUsed": 2013.4419,
        "avgHitCount": 10
      },
      "fixed-size": {
        "budget": 3000,
        "recall": 0.8605,
        "mrr": 0.6173,
        "avgTokensUsed": 2456.6512,
        "avgHitCount": 10
      }
    },
    "4000": {
      "fixed-aligned-203": {
        "budget": 4000,
        "recall": 0.8605,
        "mrr": 0.6347,
        "avgTokensUsed": 2124.7674,
        "avgHitCount": 10
      },
      "structural-nobc": {
        "budget": 4000,
        "recall": 0.8372,
        "mrr": 0.5244,
        "avgTokensUsed": 2246.5814,
        "avgHitCount": 10
      },
      "fixed-aligned-201": {
        "budget": 4000,
        "recall": 0.8605,
        "mrr": 0.6457,
        "avgTokensUsed": 2113.6279,
        "avgHitCount": 10
      },
      "structural": {
        "budget": 4000,
        "recall": 0.8372,
        "mrr": 0.5452,
        "avgTokensUsed": 2013.4419,
        "avgHitCount": 10
      },
      "fixed-size": {
        "budget": 4000,
        "recall": 0.8605,
        "mrr": 0.6173,
        "avgTokensUsed": 2456.6512,
        "avgHitCount": 10
      }
    },
    "6000": {
      "fixed-aligned-203": {
        "budget": 6000,
        "recall": 0.8605,
        "mrr": 0.6347,
        "avgTokensUsed": 2124.7674,
        "avgHitCount": 10
      },
      "structural-nobc": {
        "budget": 6000,
        "recall": 0.8372,
        "mrr": 0.5244,
        "avgTokensUsed": 2246.5814,
        "avgHitCount": 10
      },
      "fixed-aligned-201": {
        "budget": 6000,
        "recall": 0.8605,
        "mrr": 0.6457,
        "avgTokensUsed": 2113.6279,
        "avgHitCount": 10
      },
      "structural": {
        "budget": 6000,
        "recall": 0.8372,
        "mrr": 0.5452,
        "avgTokensUsed": 2013.4419,
        "avgHitCount": 10
      },
      "fixed-size": {
        "budget": 6000,
        "recall": 0.8605,
        "mrr": 0.6173,
        "avgTokensUsed": 2456.6512,
        "avgHitCount": 10
      }
    },
    "8000": {
      "fixed-aligned-203": {
        "budget": 8000,
        "recall": 0.8605,
        "mrr": 0.6347,
        "avgTokensUsed": 2124.7674,
        "avgHitCount": 10
      },
      "structural-nobc": {
        "budget": 8000,
        "recall": 0.8372,
        "mrr": 0.5244,
        "avgTokensUsed": 2246.5814,
        "avgHitCount": 10
      },
      "fixed-aligned-201": {
        "budget": 8000,
        "recall": 0.8605,
        "mrr": 0.6457,
        "avgTokensUsed": 2113.6279,
        "avgHitCount": 10
      },
      "structural": {
        "budget": 8000,
        "recall": 0.8372,
        "mrr": 0.5452,
        "avgTokensUsed": 2013.4419,
        "avgHitCount": 10
      },
      "fixed-size": {
        "budget": 8000,
        "recall": 0.8605,
        "mrr": 0.6173,
        "avgTokensUsed": 2456.6512,
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
    "对齐后的实测平均块大小与目标差 -0.39 token —— 「对齐」是近似的，这个差距是否足够小要由读者判断，不能由实现方默认成立",
    "「关掉面包屑的那一组才是在比「切点」」：结构分块开/关面包屑的实测规模是 200.9 token × 235 块 vs 203.1 token × 193 块（同一份语料、同一个 max-tokens=400）。两组「只有面包屑这一个开关不同」，所以它们的全部差异都来自「结构元数据有没有进块」这件事。⚠ 注意带面包屑那一组的平均块反而更小、块数更多（235 vs 193）——这是实测结果，不是预期方向；本报告只对齐块大小，「不对这个现象作解释」，要弄清它得单独查 StructuralChunker 的装填逻辑",
    "面包屑的取舍本身是一个「独立实验」（它是特征，不是切点）：真实系统里通常值得开（用户提问常直接用标题词，而正文可能一次都没写），这里关掉只是为了把「切点」这一个变量隔离出来",
    "等预算表的档位是 [1000, 2000, 3000, 4000, 6000, 8000]。预算口径是「装到下一个块会超预算为止」，第一条无条件放行（现实中不会因为单个块太大就一条都不给）——所以要同时看 avgTokensUsed 与 avgHitCount：预算没装满就是「块太大装不下」的直接证据",
    "本阶段检索的「词法那一路」为 BM25：它只认词面。所以对齐之后若结构分块仍然略低，结论是「在词法检索下，按结构切没有占到便宜」——「不能」推出「语义分块更差」（换真语义嵌入后词法这个约束就没了，结论可能反过来）"
  ]
}
