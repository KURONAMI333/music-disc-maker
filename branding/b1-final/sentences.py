# -*- coding: utf-8 -*-
"""失敗表示の 11 文 (kura 承認の英語原文 + 14 ロケール訳)。

用語は既訳 (music_disc_maker.playback_failed.reason.*) から採る。
訳を足したら sweep.py で行数を必ず確認する。
"""

# 制作機・金ジューク共有の 8 キー + 金ジューク専用の 3 キー。
KEYS = [
    "botcheck",
    "private",
    "region",
    "age",
    "refused",
    "connection",
    "unsupported",
    "blocked",
    "muted",
    "limit",
    "generic",
]

S = {}

S["en_us"] = {
    "botcheck": "YouTube blocked this one and no copy was found. Try another link.",
    "private": "Video is private or gone, and no copy was found. Try another link.",
    "region": "Blocked in your region, and no copy was found. Try another link.",
    "age": "Age restricted, and no copy was found. Try another link.",
    "refused": "The source refused this track. Try another link.",
    "connection": "Could not reach the source. Check your connection.",
    "unsupported": "Link not supported. Try a YouTube or direct audio link.",
    "blocked": "Address not allowed. Use a public http or https link.",
    "muted": "Volume is 0. Check Master and Jukebox/Note Blocks.",
    "limit": "Too many tracks playing. Stop one and try again.",
    "generic": "Could not play this track.",
}

S["ja_jp"] = {
    "botcheck": "YouTubeに遮断され、代わりも見つかりません。別のリンクをお試しください。",
    "private": "動画が非公開か削除済みで、代わりも見つかりません。別のリンクをお試しください。",
    "region": "お住まいの地域で遮断され、代わりも見つかりません。別のリンクをお試しください。",
    "age": "年齢制限があり、代わりも見つかりません。別のリンクをお試しください。",
    "refused": "音源がこの曲を拒否しました。別のリンクをお試しください。",
    "connection": "音源に接続できませんでした。通信環境をご確認ください。",
    "unsupported": "非対応のリンクです。YouTubeか音声の直リンクをお試しください。",
    "blocked": "使えないアドレスです。公開された http か https のリンクをご利用ください。",
    "muted": "音量が0です。主音量とジュークボックス／音符ブロックをご確認ください。",
    "limit": "同時再生数が上限です。どれかを止めてからお試しください。",
    "generic": "この曲を再生できませんでした。",
}

S["de_de"] = {
    "botcheck": "Von YouTube gesperrt, keine Kopie. Probiere einen anderen Link.",
    "private": "Video ist privat oder weg, keine Kopie. Probiere einen anderen Link.",
    "region": "In deiner Region gesperrt, keine Kopie. Probiere einen anderen Link.",
    "age": "Altersbeschränkt, keine Kopie. Probiere einen anderen Link.",
    "refused": "Die Quelle hat den Titel abgelehnt. Probiere einen anderen Link.",
    "connection": "Quelle nicht erreichbar. Prüfe deine Verbindung.",
    "unsupported": "Link nicht unterstützt. Nimm YouTube oder einen Audiolink.",
    "blocked": "Adresse nicht erlaubt. Nimm einen offenen http- oder https-Link.",
    "muted": "Lautstärke ist 0. Prüfe Gesamtlautstärke und Musikblöcke.",
    "limit": "Zu viele Titel laufen. Stoppe einen und versuche es erneut.",
    "generic": "Titel konnte nicht abgespielt werden.",
}

S["es_es"] = {
    "botcheck": "YouTube lo ha bloqueado y no hay copia. Prueba otro enlace.",
    "private": "El vídeo es privado o no existe y no hay copia. Prueba otro enlace.",
    "region": "Bloqueado en tu región y no hay copia. Prueba otro enlace.",
    "age": "Tiene restricción de edad y no hay copia. Prueba otro enlace.",
    "refused": "La fuente rechazó esta pista. Prueba otro enlace.",
    "connection": "No se pudo contactar con la fuente. Revisa tu conexión.",
    "unsupported": "Enlace no compatible. Usa YouTube o un enlace de audio directo.",
    "blocked": "Dirección no permitida. Usa un enlace http o https público.",
    "muted": "El volumen está en 0. Revisa Volumen general y Bloques musicales.",
    "limit": "Suenan demasiadas pistas. Detén una e inténtalo de nuevo.",
    "generic": "No se pudo reproducir esta pista.",
}

S["fr_fr"] = {
    "botcheck": "Bloqué par YouTube, aucune copie. Essayez un autre lien.",
    "private": "Vidéo privée ou supprimée, aucune copie. Essayez un autre lien.",
    "region": "Bloquée dans votre région, aucune copie. Essayez un autre lien.",
    "age": "Limite d'âge, aucune copie. Essayez un autre lien.",
    "refused": "La source a refusé cette piste. Essayez un autre lien.",
    "connection": "Source injoignable. Vérifiez votre connexion.",
    "unsupported": "Lien non pris en charge. Utilisez YouTube ou un lien audio direct.",
    "blocked": "Adresse non autorisée. Utilisez un lien http ou https public.",
    "muted": "Le volume est à 0. Vérifiez Volume principal et Blocs musicaux.",
    "limit": "Trop de pistes en cours. Arrêtez-en une et réessayez.",
    "generic": "Impossible de lire cette piste.",
}

S["it_it"] = {
    "botcheck": "Bloccato da YouTube e nessuna copia trovata. Prova un altro link.",
    "private": "Il video è privato o rimosso e non c'è copia. Prova un altro link.",
    "region": "Bloccato nella tua regione e non c'è copia. Prova un altro link.",
    "age": "Ha restrizioni di età e non c'è copia. Prova un altro link.",
    "refused": "La sorgente ha rifiutato questa traccia. Prova un altro link.",
    "connection": "Sorgente irraggiungibile. Controlla la connessione.",
    "unsupported": "Link non supportato. Usa YouTube o un link audio diretto.",
    "blocked": "Indirizzo non permesso. Usa un link http o https pubblico.",
    "muted": "Il volume è a 0. Controlla Volume generale e Dischi e blocchi sonori.",
    "limit": "Troppe tracce in riproduzione. Fermane una e riprova.",
    "generic": "Impossibile riprodurre questa traccia.",
}

S["ko_kr"] = {
    "botcheck": "YouTube가 차단했고 대체 음원도 없습니다. 다른 링크를 사용해 보세요.",
    "private": "동영상이 비공개이거나 삭제됐고 대체 음원도 없습니다. 다른 링크를 사용해 보세요.",
    "region": "현재 지역에서 차단됐고 대체 음원도 없습니다. 다른 링크를 사용해 보세요.",
    "age": "연령 제한이 있고 대체 음원도 없습니다. 다른 링크를 사용해 보세요.",
    "refused": "음원이 이 트랙을 거부했습니다. 다른 링크를 사용해 보세요.",
    "connection": "음원에 연결하지 못했습니다. 인터넷 연결을 확인해 보세요.",
    "unsupported": "지원하지 않는 링크입니다. YouTube나 오디오 직링크를 사용해 보세요.",
    "blocked": "사용할 수 없는 주소입니다. 공개된 http나 https 링크를 사용하세요.",
    "muted": "음량이 0입니다. 전체 음량과 주크박스/소리 블록을 확인해 보세요.",
    "limit": "재생 중인 트랙이 너무 많습니다. 하나를 멈추고 다시 시도하세요.",
    "generic": "이 트랙을 재생하지 못했습니다.",
}

S["nl_nl"] = {
    "botcheck": "Door YouTube geblokkeerd, geen kopie. Probeer een andere link.",
    "private": "Video is privé of weg, geen kopie. Probeer een andere link.",
    "region": "Geblokkeerd in jouw regio, geen kopie. Probeer een andere link.",
    "age": "Leeftijdsgrens, geen kopie. Probeer een andere link.",
    "refused": "De bron weigerde dit nummer. Probeer een andere link.",
    "connection": "Bron niet bereikbaar. Controleer je verbinding.",
    "unsupported": "Link niet ondersteund. Gebruik YouTube of een directe audiolink.",
    "blocked": "Adres niet toegestaan. Gebruik een openbare http- of https-link.",
    "muted": "Het volume is 0. Controleer Hoofdvolume en Platenspeler/nootblokken.",
    "limit": "Te veel nummers spelen. Stop er een en probeer opnieuw.",
    "generic": "Kan dit nummer niet afspelen.",
}

S["pl_pl"] = {
    "botcheck": "Zablokowane przez YouTube, brak kopii. Spróbuj innego linku.",
    "private": "Film jest prywatny lub usunięty, brak kopii. Spróbuj innego linku.",
    "region": "Zablokowane w Twoim regionie, brak kopii. Spróbuj innego linku.",
    "age": "Ograniczenie wiekowe, brak kopii. Spróbuj innego linku.",
    "refused": "Źródło odrzuciło ten utwór. Spróbuj innego linku.",
    "connection": "Nie można połączyć się ze źródłem. Sprawdź połączenie.",
    "unsupported": "Link nieobsługiwany. Użyj YouTube lub linku audio.",
    "blocked": "Adres niedozwolony. Użyj publicznego linku http lub https.",
    "muted": "Głośność wynosi 0. Sprawdź Ogólny poziom głośności i Bloki dźwiękowe.",
    "limit": "Gra zbyt wiele utworów. Zatrzymaj jeden i spróbuj ponownie.",
    "generic": "Nie można odtworzyć tego utworu.",
}

S["pt_br"] = {
    "botcheck": "Bloqueado pelo YouTube e sem cópia. Tente outro link.",
    "private": "O vídeo é privado ou sumiu e não há cópia. Tente outro link.",
    "region": "Bloqueado na sua região e não há cópia. Tente outro link.",
    "age": "Tem restrição de idade e não há cópia. Tente outro link.",
    "refused": "A fonte recusou esta faixa. Tente outro link.",
    "connection": "Não foi possível alcançar a fonte. Verifique sua conexão.",
    "unsupported": "Link não compatível. Use o YouTube ou um link de áudio direto.",
    "blocked": "Endereço não permitido. Use um link http ou https público.",
    "muted": "O volume está em 0. Verifique Volume principal e Blocos musicais.",
    "limit": "Faixas demais tocando. Pare uma e tente de novo.",
    "generic": "Não foi possível tocar esta faixa.",
}

S["ru_ru"] = {
    "botcheck": "YouTube заблокировал, замены нет. Нужна другая ссылка.",
    "private": "Видео скрыто или удалено, замены нет. Нужна другая ссылка.",
    "region": "Недоступно в вашем регионе, замены нет. Нужна другая ссылка.",
    "age": "Возрастное ограничение, замены нет. Нужна другая ссылка.",
    "refused": "Источник отказал в этом треке. Нужна другая ссылка.",
    "connection": "Не удалось связаться с источником. Проверьте сеть.",
    "unsupported": "Ссылка не поддерживается. Нужен YouTube или прямая ссылка.",
    "blocked": "Адрес не разрешён. Нужна публичная ссылка http или https.",
    "muted": "Громкость равна 0. Проверьте Общую громкость и Музыкальные блоки.",
    "limit": "Слишком много треков играет. Остановите один и повторите.",
    "generic": "Не удалось воспроизвести трек.",
}

S["uk_ua"] = {
    "botcheck": "YouTube заблокував, заміни немає. Інше посилання?",
    "private": "Відео приховане або видалене, заміни немає. Інше посилання?",
    "region": "Недоступне у вашому регіоні, заміни немає. Інше посилання?",
    "age": "Вікове обмеження, заміни немає. Інше посилання?",
    "refused": "Джерело відмовило в цьому треку. Інше посилання?",
    "connection": "Не вдалося зв'язатися з джерелом. Перевірте з'єднання.",
    "unsupported": "Посилання не підтримується. Потрібен YouTube або пряме.",
    "blocked": "Адреса не дозволена. Потрібне публічне http або https.",
    "muted": "Гучність дорівнює 0. Перевірте Загальну гучність і нотні блоки.",
    "limit": "Забагато треків грає. Зупиніть один і повторіть.",
    "generic": "Не вдалося відтворити трек.",
}

S["zh_cn"] = {
    "botcheck": "已被 YouTube 封锁，也没有找到替代。请换一个链接。",
    "private": "视频为私享或已删除，也没有找到替代。请换一个链接。",
    "region": "在你所在的地区被封锁，也没有找到替代。请换一个链接。",
    "age": "有年龄限制，也没有找到替代。请换一个链接。",
    "refused": "音源拒绝了这段音频。请换一个链接。",
    "connection": "无法连接到音源。请检查网络连接。",
    "unsupported": "不支持该链接。请用 YouTube 或音频直链。",
    "blocked": "不允许该地址。请用公开的 http 或 https 链接。",
    "muted": "音量为 0。请检查主音量和唱片机/音符盒。",
    "limit": "同时播放的音频过多。请停止一个后重试。",
    "generic": "无法播放这段音频。",
}

S["zh_tw"] = {
    "botcheck": "已被 YouTube 封鎖，也沒有找到替代。請換一個連結。",
    "private": "影片為私人或已刪除，也沒有找到替代。請換一個連結。",
    "region": "在你所在的地區被封鎖，也沒有找到替代。請換一個連結。",
    "age": "有年齡限制，也沒有找到替代。請換一個連結。",
    "refused": "音源拒絕了這段音訊。請換一個連結。",
    "connection": "無法連線到音源。請檢查網路連線。",
    "unsupported": "不支援此連結。請用 YouTube 或音訊直連。",
    "blocked": "不允許此位址。請用公開的 http 或 https 連結。",
    "muted": "音量為 0。請檢查主音量和唱片機／音階盒。",
    "limit": "同時播放的音訊過多。請停止一個後重試。",
    "generic": "無法播放這段音訊。",
}
