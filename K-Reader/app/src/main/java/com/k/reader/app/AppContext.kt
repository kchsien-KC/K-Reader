package com.k.reader.app

import android.app.Application
import com.k.reader.data.AppDatabase
import com.k.reader.data.appDb
import splitties.init.appCtx

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — AppContext 橋接層（Stage 13 完整樹整合）
 * ═══════════════════════════════════════════════════════════════
 *
 *  背景：舊版「閱讀」以頂層套件的 AppContext（ctx／db）供全程
 *  式取用；2025-12 基準樹已改走 splitties appCtx＋data.appDb。
 *  K.閱讀 55 個增益模組一律以 com.k.reader.app.AppContext 取環境
 *  ——本橋接層以 6 行委託對齊新引擎，模組端零改動：
 *
 *    · ctx：Application（splitties.init.appCtx 委託）
 *    · db ：Room AppDatabase（com.k.reader.data.appDb 委託）
 *
 *  單源紀律：本檔只做委託，不另持狀態——引擎換源時僅改此處。
 */
object AppContext {

    /** 全域 Application 上下文 */
    val ctx: Application
        get() = appCtx

    /** Room 資料庫單源 */
    val db: AppDatabase
        get() = appDb
}
