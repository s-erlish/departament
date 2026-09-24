package com.v2ray.ang.ui

import android.content.Intent
import android.os.Bundle
import androidx.core.view.isVisible
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityUpdateOfferBinding
import com.v2ray.ang.handler.AppUpdateWatcher
import com.v2ray.ang.ui.component.EmptyStateBinder
import com.v2ray.ang.ui.component.Haptic
import com.v2ray.ang.ui.component.ReleaseNotesText
import com.v2ray.ang.ui.component.onSingleClick
import com.v2ray.ang.ui.component.pressFeedback

/**
 * «Вышла версия X» на весь экран. Открывает его главное окно при запуске приложения, раз за запуск
 * ([AppUpdateWatcher.takeOffer]); разметка и её обоснование — `activity_update_offer.xml`.
 *
 * «Обновить» ведёт на экран обновления, который сразу начинает скачивание
 * ([CheckUpdateActivity.EXTRA_START_DOWNLOAD]); «Не сейчас», ✕ и «назад» просто закрывают окно.
 */
class UpdateOfferActivity : BaseActivity() {

    private val binding by lazy { ActivityUpdateOfferBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val version = AppUpdateWatcher.availableVersion()
        if (version == null) {
            finish()
            return
        }
        setContentView(binding.root)

        EmptyStateBinder.bind(
            root = binding.offerHero.root,
            glyph = R.drawable.ic_cloud_download_24dp,
            title = getString(R.string.update_offer_title, version),
            line = getString(R.string.update_offer_line),
        )

        val notes = ReleaseNotesText.whatsNew(AppUpdateWatcher.notes(), binding.tvOfferNotes.paint)
        binding.tvOfferNotes.text = notes
        binding.tvOfferNotes.isVisible = !notes.isNullOrEmpty()
        binding.tvOfferWhatsNewTitle.isVisible = !notes.isNullOrEmpty()

        binding.offerUpdate.actionButton.setText(R.string.update_offer_update)
        binding.offerUpdate.actionButton.onSingleClick(Haptic.PRESS) {
            startActivity(
                Intent(this, CheckUpdateActivity::class.java)
                    .putExtra(CheckUpdateActivity.EXTRA_START_DOWNLOAD, true)
            )
            finish()
        }
        binding.offerLater.actionButton.setText(R.string.update_offer_later)
        binding.offerLater.actionButton.onSingleClick { finish() }
        binding.btnOfferClose.pressFeedback(R.anim.press_icon)
        binding.btnOfferClose.onSingleClick { finish() }
    }
}
