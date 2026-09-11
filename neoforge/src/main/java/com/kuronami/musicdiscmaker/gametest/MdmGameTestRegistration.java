package com.kuronami.musicdiscmaker.gametest;

//? if >=1.21.2 {
import com.kuronami.musicdiscmaker.MusicDiscMaker;

import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * 26.2 のデータ駆動 GameTest 登録層。{@link RegisterGameTestsEvent} (Mod バス) の
 * {@code registerTest} は {@code GameTestInstance} を直接受け取るので、テストメソッドを
 * {@link MethodGameTestInstance} に載せて {@code music_disc_maker} 名前空間へ登録する。
 *
 * <p>structure は 1.21.1 の {@code @GameTest(template = "empty8x3x8")} と同じテンプレート
 * ({@code data/music_disc_maker/structure/empty8x3x8.nbt}) を指す。batch の代わりに
 * environment を登録し、それがそのまま batch キーになる (1.21.1 では batch 名、26.2 では
 * environment 名でグルーピングされる)。{@code ActiveDiscRegistry} は static な global state
 * なので、{@code clear()} を撃つテスト同士は environment を分けて順次実行にする。
 *
 * <p>environment は 1.21.1 の batch 名を snake_case に写した ID で登録する
 * ({@code Identifier} の path は [a-z0-9/._-] に制限されるため)。
 */
public final class MdmGameTestRegistration {

    private MdmGameTestRegistration() {
    }

    //? if >=26.1 {
    private static Holder<TestEnvironmentDefinition<?>> env(RegisterGameTestsEvent event, String name) {
        return event.registerEnvironment(Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID, name));
    }
    //?} else {
    /*private static Holder<TestEnvironmentDefinition> env(RegisterGameTestsEvent event, String name) {
        return event.registerEnvironment(Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID, name));
    }
    *///?}

    public static void register(RegisterGameTestsEvent event) {
        //? if >=26.1 {
        final Holder<TestEnvironmentDefinition<?>> empty = event.registerEnvironment(
                Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID, "gametest"));
        //?} else {
        /*final Holder<TestEnvironmentDefinition> empty = event.registerEnvironment(
                Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID, "gametest"));
        *///?}
        event.registerTest(
                Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID, "clearing_one_dimension_leaves_the_others_alone"),
                new MethodGameTestInstance(
                        ActiveDiscReleaseGameTests::clearingOneDimensionLeavesTheOthersAlone,
                        new TestData<>(empty,
                                Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID, "empty8x3x8"),
                                100, 0, true)));
        register(event, "moving_vanilla_without_speakers", SpeakerPlaybackGameTests::movingVanillaWithoutSpeakersUsesManagedRoute, empty, 100);
        register(event, "new_blocks_have_recipe_unlocks", RecipeDiscoveryGameTests::newBlocksHaveLoadedRecipeUnlocks, empty, 100);
        register(event, "golden_emitter_fixed_source_live_settings", com.kuronami.musicdiscmaker.client.audio.GoldenEmitterAnchorGameTests::fixedGoldenWithoutSpeakersUsesLiveSettings, empty, 100);
        register(event, "golden_emitter_speaker_snapshot_authority", com.kuronami.musicdiscmaker.client.audio.GoldenEmitterAnchorGameTests::speakerNetworkKeepsServerSnapshotAuthoritative, empty, 100);
        register(event, "maker_single_disc_atomicity", MusicDiscMakerSingleDiscGameTests::createsOnlyCanonicalSingleDiscAndKeepsInputsAtomic, empty, 100);
        register(event, "maker_menu_legacy_materials", MusicDiscMakerSingleDiscGameTests::menuOnlyExposesTwoMachineSlotsAndRecoversLegacyMaterialOnce, empty, 100);
        register(event, "maker_single_disc_save_reload", MusicDiscMakerSingleDiscGameTests::singleDiscAndResolvedCacheSurviveSaveReload, empty, 100);
        register(event, "maker_destroy_drops", MusicDiscMakerSingleDiscGameTests::destroyingMakerDropsEveryStoredStackExactlyOnce, empty, 100);
        register(event, "album_recipe_assembles_and_rejects_wrong_grid",
                RecipeAssemblyGameTests::albumRecipeAssemblesAndRejectsWrongGrid, empty, 100);
        register(event, "speaker_recipe_assembles_and_rejects_wrong_grid",
                RecipeAssemblyGameTests::speakerRecipeAssemblesAndRejectsWrongGrid, empty, 100);
        register(event, "captured_album_keeps_selection_and_state",
                GoldenPlaybackStateGameTests::capturedPlaybackKeepsSelectionAndDoesNotRegisterOrResumeStoppedMedia,
                empty, 100);
        register(event, "clear_content_stops_playback", GoldenPlaybackStateGameTests::clearContentStopsPlayback, empty, 100);
        register(event, "album_end_restarts_with_one_resume",
                GoldenPlaybackStateGameTests::albumEndRestartsWithOneResume, empty, 100);
        register(event, "stopped_album_stays_stopped_after_reload",
                GoldenPlaybackStateGameTests::stoppedAlbumStaysStoppedAfterReload, empty, 100);
        register(event, "paused_album_keeps_disc_and_offset_after_reload",
                GoldenPlaybackStateGameTests::pausedAlbumKeepsDiscAndOffsetAfterReload, empty, 100);
        register(event, "legacy_paused_album_keeps_disc_and_offset",
                GoldenPlaybackStateGameTests::legacyPausedAlbumKeepsDiscAndOffset, empty, 100);
        register(event, "legacy_stopped_album_does_not_replay_on_load",
                GoldenPlaybackStateGameTests::legacyStoppedAlbumDoesNotReplayOnLoad, empty, 100);
        register(event, "source_identity_survives_relocation_and_legacy_data", GoldenPlaybackStateGameTests::sourceIdentitySurvivesRelocationAndLegacyData, empty, 100);
        register(event, "source_registry_conflict_removal_reload", GoldenPlaybackStateGameTests::sourceRegistryTracksConflictRemovalAndReload, empty, 100);
        register(event, "source_retirements_roundtrip_revive", GoldenPlaybackStateGameTests::sourceRetirementsRoundTripAndRevive, empty, 100);
        register(event, "golden_media_manual_transport", GoldenMediaSequenceGameTests::manualTransportCrossesAlbumDiscs, empty, 400);
        register(event, "pedestal_survival_swap", com.kuronami.musicdiscmaker.block.DiscPedestalInteractionGameTests::survivalSwapConsumesOneAndReturnsPrevious, empty, 100);
        register(event, "pedestal_full_inventory_swap", com.kuronami.musicdiscmaker.block.DiscPedestalInteractionGameTests::fullInventoryDropsPreviousExactlyOnce, empty, 100);
        register(event, "pedestal_empty_hand_creative", com.kuronami.musicdiscmaker.block.DiscPedestalInteractionGameTests::emptyHandAndCreativeKeepExistingSemantics, empty, 100);
        register(event, "pedestal_outline_and_track_metadata", com.kuronami.musicdiscmaker.block.DiscPedestalInteractionGameTests::selectionOutlineIsWholeAndTrackMetadataFollowsSwap, empty, 100);
        register(event, "golden_media_live_repeat_pause", GoldenMediaSequenceGameTests::liveManualNextRepeatTailAndPausedPreviousAreDistinct, empty, 400);
        register(event, "golden_media_album_reload", GoldenMediaSequenceGameTests::albumDiscAndTrackPositionSurviveReload, empty, 400);
        register(event, "golden_shuffle_reload_transport", GoldenMediaSequenceGameTests::shufflePreservesPositionOffsetAndFixedTransportAfterReload, empty, 400);
        register(event, "oversize_source_count_mutation", OversizeMediaRecoveryGameTests::sourceCountChangeRollsBackRecovery, empty, 400);
        register(event, "oversize_placed_commit_exception", OversizeMediaRecoveryGameTests::placedCommitExceptionRestoresMedia, empty, 400);
        register(event, "oversize_album_content_change_rollback", OversizeMediaRecoveryGameTests::albumContentChangeDiscardsSpawnedEntities, empty, 400);
        register(event, "oversize_recovery_stale_commit_rollback", OversizeMediaRecoveryGameTests::recoveryStaleCommitDiscardsSpawnedEntities, empty, 400);
        register(event, "oversize_single_disc_and_placed_guards", OversizeMediaRecoveryGameTests::oversizedDiscAndPlacedBoomboxAreUntouched, empty, 400);
        register(event, "oversize_album_recovery_order_and_rollback", OversizeMediaRecoveryGameTests::albumRecoveryPreservesOrderAndSpawnFailurePreservesSource, empty, 400);
        register(event, "oversize_boombox_album_recovery_and_inventory_only", OversizeMediaRecoveryGameTests::boomboxAlbumRecoveryAndInventoryOnlyRejection, empty, 400);
        register(event, "boombox_contents_legacy_defaults_roundtrip", BoomboxControlGameTests::legacyContentsDefaultAndExtendedStateRoundTrip, empty, 100);
        register(event, "boombox_menu_stores_and_takes_single_medium", BoomboxMenuGameTests::storesAndTakesSingleMedium, empty, 200);
        register(event, "boombox_menu_inserting_medium_starts_carried_and_placed",
                BoomboxMenuGameTests::insertingMediumStartsCarriedAndPlacedAndCanPause, empty, 200);
        register(event, "boombox_main_hand_menu_sync", BoomboxHeldMenuSyncGameTests::mainHandOpenKeepsIdentityAcrossCursorUpdates, empty, 200);
        register(event, "boombox_stale_generation_recovery", BoomboxHeldMenuSyncGameTests::staleInitialGenerationIsRejectedThenCurrentAccepted, empty, 200);
        register(event, "boombox_menu_stale_and_reverse_swap_preserve_source", BoomboxMenuGameTests::staleAndReverseSwapPreserveSource, empty, 200);
        register(event, "boombox_menu_initial_sync_keeps_existing_machine_untouched", BoomboxMenuGameTests::initialSyncKeepsExistingMachineUntouched, empty, 200);
        register(event, "boombox_menu_placed_source_and_storage_rejection_preserve_stacks", BoomboxMenuGameTests::placedSourceAndStorageRejectionPreserveStacks, empty, 200);
        register(event, "boombox_media_generation", BoomboxPlaybackStateGameTests::replacingMediaClearsPositionWithoutResettingGeneration, empty, 200);
        register(event, "boombox_sessionless_no_resume", BoomboxPlaybackStateGameTests::sessionlessPlayingStateDoesNotOfferImplicitResume, empty, 200);
        register(event, "boombox_carry_placed_lifecycle", BoomboxPlaybackStateGameTests::carriedPlacedStaleLifecycleKeepsCursorAndNeverAutoRestarts, empty, 200);
        register(event, "boombox_creative_placement_identity_controls",
                BoomboxPlaybackStateGameTests::creativePlacementTransfersPlaybackAndLeavesIndependentPausedCopy,
                empty, 200);
        register(event, "boombox_survival_placement_continuity",
                BoomboxPlaybackStateGameTests::survivalPlacementKeepsThePlayingMachineAndConsumesTheItem,
                empty, 200);
        register(event, "boombox_existing_placed_carried_collision_recovers",
                BoomboxPlaybackStateGameTests::existingPlacedAndPausedCarriedCollisionSelfHeals,
                empty, 200);
        register(event, "boombox_existing_placed_duplicates_independent",
                BoomboxPlaybackStateGameTests::existingPlacedDuplicatesBecomeIndependentBeforeFirstControl,
                empty, 200);
        register(event, "boombox_unclaimed_placed_duplicate_removal",
                BoomboxPlaybackStateGameTests::removingUnclaimedPlacedDuplicateDoesNotStopOwner,
                empty, 200);
        register(event, "boombox_tail_grace", BoomboxPlaybackStateGameTests::tailGraceDelaysSequenceAdvance, empty, 200);
        register(event, "boombox_manual_wrap", BoomboxPlaybackStateGameTests::manualNavigationWrapsBothDirectionsAndPreservesPause, empty, 200);
        register(event, "boombox_natural_tail_repeat", BoomboxPlaybackStateGameTests::naturalTailStillUsesRepeatSetting, empty, 200);
        register(event, "boombox_control_wire", BoomboxControlGameTests::wireRetainsLongGenerationAndAtomicState, empty, 100);
        register(event, "boombox_control_guard", BoomboxControlGameTests::controlsRequireCurrentMenuSourceAndGeneration, empty, 100);
        register(event, "golden_shuffle_missing_saved_fields", GoldenMediaSequenceGameTests::shuffleMissingSavedSeedAndAnchorFallsBackSafely, empty, 400);
        register(event, "playback_same_url_logical_position", PlaybackSessionGameTests::sameUrlAtAnotherLogicalPositionNeedsStopBeforePlay, empty, 100);
        register(event, "golden_navigation_menu_generation", GoldenNavigationGameTests::navigationRequiresOpenMenuAndCurrentGeneration, empty, 400);
        register(event, "album_menu_storage_decode_limit", AlbumMenuGameTests::storageDecodeLimitRejectsInsertionButKeepsExistingContents, empty, 400);
        register(event, "album_menu_initial_sync_budget", AlbumMenuGameTests::initialMenuSyncRejectsUnsafeExistingAlbum, empty, 400);
        register(event, "codec_round_trip_keeps_disc_metadata", AlbumContentsGameTests::codecRoundTripKeepsDiscMetadata, empty, 100);
        register(event, "album_dyeing_keeps_metadata_and_contents",
                AlbumDyeingGameTests::dyeingKeepsAlbumMetadataAndContents, empty, 100);
        register(event, "album_dyeing_rejects_extra_wrong_and_same_color",
                AlbumDyeingGameTests::dyeingRejectsExtraWrongAndSameColorInputs, empty, 100);
        register(event, "stack_copies_do_not_share_mutable_ownership", AlbumContentsGameTests::stackCopiesDoNotShareMutableOwnership, empty, 100);
        register(event, "nested_album_is_rejected_before_recursive_codec_expansion", AlbumContentsGameTests::nestedAlbumIsRejectedBeforeRecursiveCodecExpansion, empty, 100);
        register(event, "malformed_wire_limits_fail_before_item_expansion", AlbumContentsGameTests::malformedWireLimitsFailBeforeItemExpansion, empty, 100);
        register(event, "album_menu_opening_and_taking_keeps_overflow", AlbumMenuGameTests::openingAndTakingKeepsOverflow, empty, 100);
        register(event, "album_menu_recovers_overflow_same_session", AlbumMenuGameTests::menuRecoversOverflowInSameSession, empty, 400);
        register(event, "album_menu_storage_budget_all_insert_paths", AlbumMenuGameTests::storageBudgetRejectsEveryInsertPathWithoutLoss, empty, 400);
        register(event, "album_and_boombox_size_measurements", AlbumSizeGameTests::albumAndBoomboxCodecMeasurements, empty, 400);
        register(event, "album_long_metadata_wire_boundary", AlbumSizeGameTests::fullLengthMetadataIsRejectedAtAlbumWireBoundary, empty, 400);
        register(event, "media_raw_album_kinds", MediaSequenceGameTests::rawAndAlbumResolveToDistinctKinds, empty, 100);
        register(event, "media_album_positions_duplicate_urls", MediaSequenceGameTests::albumKeepsDiscTrackPositionsAndDuplicateUrls, empty, 100);
        register(event, "media_invalid_discs_keep_positions", MediaSequenceGameTests::invalidDiscsAreSkippedWithoutRenumberingAlbumPositions, empty, 100);
        register(event, "media_live_unknown_manual_advance", MediaSequenceGameTests::liveAndUnknownOnlyBlockAutomaticAdvance, empty, 100);
        register(event, "album_menu_moved_source_cannot_save_into_replacement", AlbumMenuGameTests::movedSourceCannotSaveIntoReplacement, empty, 100);
        register(event, "album_menu_stores_takes_and_reorders", AlbumMenuGameTests::storesTakesAndReordersThroughMenu, empty, 100);
        register(event, "album_menu_source_slot_rejects_all_move_clicks", AlbumMenuGameTests::sourceSlotRejectsAllMoveClicks, empty, 100);
        register(event, "album_menu_reverse_hotbar_swap_preserves_source", AlbumMenuGameTests::reverseHotbarSwapPreservesSourceAndMetadata, empty, 100);
        register(event, "album_menu_reverse_offhand_swap_preserves_all", AlbumMenuGameTests::reverseOffhandSwapAndInvalidClicksPreserveAll, empty, 100);
        register(event, "active_disc_save_data_survives_the_nbt_round_trip",
                ActiveDiscReleaseGameTests::activeDiscSaveDataSurvivesTheNbtRoundTrip, empty, 100);
        register(event, "repeat_restart_marker_forces_stop_then_play_resend",
                GramophoneLoopResendGameTests::repeatRestartMarkerForcesStopThenPlayResend,
                env(event, "gramophone_loop_resend"), 200);
        register(event, "wire_format_and_protocol_version_move_together",
                NetworkProtocolGameTests::wireFormatAndProtocolVersionMoveTogether, empty, 100);
        register(event, "directional_survives_the_round_trip",
                NetworkProtocolGameTests::directionalSurvivesTheRoundTrip, empty, 100);
        register(event, "stop_during_load_discards_the_pending_source",
                PlaybackGenerationGameTests::stopDuringLoadDiscardsThePendingSource, empty, 100);
        register(event, "second_play_cancels_the_first_load",
                PlaybackGenerationGameTests::secondPlayCancelsTheFirstLoad, empty, 100);
        register(event, "different_keys_do_not_cancel_each_other",
                PlaybackGenerationGameTests::differentKeysDoNotCancelEachOther, empty, 100);
        register(event, "unknown_key_is_never_current",
                PlaybackGenerationGameTests::unknownKeyIsNeverCurrent, empty, 100);
        register(event, "fault_pushed_from_the_playback_thread_reaches_the_user",
                CompatFaultWiringGameTests::faultPushedFromThePlaybackThreadReachesTheUser, empty, 100);
        register(event, "push_and_pull_of_the_same_fault_report_only_once",
                CompatFaultWiringGameTests::pushAndPullOfTheSameFaultReportOnlyOnce, empty, 100);
        register(event, "sources_without_the_push_hook_still_report_through_pull",
                CompatFaultWiringGameTests::sourcesWithoutThePushHookStillReportThroughPull, empty, 100);
        register(event, "a_fault_that_happened_before_wiring_is_replayed",
                CompatFaultWiringGameTests::aFaultThatHappenedBeforeWiringIsReplayed, empty, 100);
        register(event, "retries_after_a_failed_attempt",
                DependencyLoadGameTests::retriesAfterAFailedAttempt, empty, 100);
        register(event, "stops_retrying_but_keeps_reporting_the_cause",
                DependencyLoadGameTests::stopsRetryingButKeepsReportingTheCause, empty, 100);
        register(event, "an_empty_dependency_set_fails_with_a_diagnosable_message",
                DependencyLoadGameTests::anEmptyDependencySetFailsWithADiagnosableMessage, empty, 100);
        register(event, "reuse_requires_the_marker_and_matching_sizes",
                DependencyLoadGameTests::reuseRequiresTheMarkerAndMatchingSizes, empty, 100);
        register(event, "the_extraction_directory_tracks_the_dependency_set",
                DependencyLoadGameTests::theExtractionDirectoryTracksTheDependencySet, empty, 100);
        register(event, "an_accepted_sound_is_left_alone",
                SoundEngineAcceptanceGameTests::anAcceptedSoundIsLeftAlone, empty, 100);
        register(event, "a_rejected_sound_is_reported_instead_of_showing_now_playing",
                SoundEngineAcceptanceGameTests::aRejectedSoundIsReportedInsteadOfShowingNowPlaying, empty, 100);
        register(event, "a_sound_dropped_for_zero_volume_says_so",
                SoundEngineAcceptanceGameTests::aSoundDroppedForZeroVolumeSaysSo, empty, 100);
        register(event, "fault_confirmed_after_the_stream_ended_still_reaches",
                PlaybackFaultDeliveryGameTests::faultConfirmedAfterTheStreamEndedStillReaches, empty, 100);
        register(event, "fault_confirmed_before_the_sink_is_registered_is_replayed",
                PlaybackFaultDeliveryGameTests::faultConfirmedBeforeTheSinkIsRegisteredIsReplayed, empty, 100);
        register(event, "sources_without_the_push_hook_fall_back_to_pull",
                PlaybackFaultDeliveryGameTests::sourcesWithoutThePushHookFallBackToPull, empty, 100);
        final var discDurationEnv = env(event, "disc_duration");
        register(event, "vanilla_disc_reports_duration",
                DiscDurationGameTests::vanillaDiscReportsDuration, discDurationEnv, 100);
        register(event, "custom_disc_reports_real_duration_not_silent_bucket",
                DiscDurationGameTests::customDiscReportsRealDurationNotSilentBucket, discDurationEnv, 100);
        register(event, "empty_jukebox_has_no_duration",
                DiscDurationGameTests::emptyJukeboxHasNoDuration, discDurationEnv, 100);
        register(event, "hopper_insert_starts_playback",
                GoldenJukeboxHopperGameTests::hopperInsertStartsPlayback, empty, 200);
        register(event, "hopper_extracts_after_playback_stops",
                GoldenJukeboxHopperGameTests::hopperExtractsAfterPlaybackStops, empty, 200);
        register(event, "hopper_extracts_after_real_track_ends",
                GoldenJukeboxHopperGameTests::hopperExtractsAfterRealTrackEnds, empty, 600);
        register(event, "periodic_resend_applies_the_current_settings_live",
                LivePlaybackRegistryGameTests::periodicResendAppliesTheCurrentSettingsLive, empty, 100);
        register(event, "every_resend_keeps_applying_the_latest_settings",
                LivePlaybackRegistryGameTests::everyResendKeepsApplyingTheLatestSettings, empty, 100);
        register(event, "changing_the_track_stops_the_old_voice_and_loads_the_new_one",
                LivePlaybackRegistryGameTests::changingTheTrackStopsTheOldVoiceAndLoadsTheNewOne, empty, 100);
        register(event, "a_load_that_finishes_after_the_track_changed_is_discarded",
                LivePlaybackRegistryGameTests::aLoadThatFinishesAfterTheTrackChangedIsDiscarded, empty, 100);
        register(event, "resends_during_the_initial_load_do_not_stack_or_cancel_it",
                LivePlaybackRegistryGameTests::resendsDuringTheInitialLoadDoNotStackOrCancelIt, empty, 100);
        register(event, "a_failed_url_is_not_retried_immediately",
                LivePlaybackRegistryGameTests::aFailedUrlIsNotRetriedImmediately, empty, 100);
        register(event, "a_transient_failure_recovers_on_its_own",
                LivePlaybackRegistryGameTests::aTransientFailureRecoversOnItsOwn, empty, 100);
        register(event, "repeated_failures_back_off_but_never_give_up",
                LivePlaybackRegistryGameTests::repeatedFailuresBackOffButNeverGiveUp, empty, 100);
        register(event, "the_same_reason_is_reported_once_until_playback_succeeds_again",
                LivePlaybackRegistryGameTests::theSameReasonIsReportedOnceUntilPlaybackSucceedsAgain, empty, 100);
        register(event, "a_warm_source_is_handed_to_the_playback_that_asks_for_it",
                PlaybackPrefetchGameTests::aWarmSourceIsHandedToThePlaybackThatAsksForIt, empty, 100);
        register(event, "a_miss_returns_null_so_the_normal_load_still_runs",
                PlaybackPrefetchGameTests::aMissReturnsNullSoTheNormalLoadStillRuns, empty, 100);
        register(event, "changing_the_next_track_closes_the_old_prefetch",
                PlaybackPrefetchGameTests::changingTheNextTrackClosesTheOldPrefetch, empty, 100);
        register(event, "repeated_requests_for_the_same_track_do_not_stack",
                PlaybackPrefetchGameTests::repeatedRequestsForTheSameTrackDoNotStack, empty, 100);
        register(event, "an_expired_prefetch_is_closed_and_never_handed_out",
                PlaybackPrefetchGameTests::anExpiredPrefetchIsClosedAndNeverHandedOut, empty, 100);
        register(event, "a_request_with_an_offset_does_not_get_the_head_of_the_track",
                PlaybackPrefetchGameTests::aRequestWithAnOffsetDoesNotGetTheHeadOfTheTrack, empty, 100);
        register(event, "every_cancellation_path_closes_exactly_once",
                PlaybackPrefetchGameTests::everyCancellationPathClosesExactlyOnce, empty, 100);
        register(event, "new_prefetches_are_not_started_once_the_limit_is_reached",
                PlaybackPrefetchGameTests::newPrefetchesAreNotStartedOnceTheLimitIsReached, empty, 100);
        register(event, "single_track_repeat_warms_the_url_it_is_already_playing",
                PlaybackPrefetchGameTests::singleTrackRepeatWarmsTheUrlItIsAlreadyPlaying, empty, 100);
        register(event, "a_restarted_track_keeps_the_prefetch_made_for_its_current_url",
                PlaybackPrefetchGameTests::aRestartedTrackKeepsThePrefetchMadeForItsCurrentUrl, empty, 100);
        final var pocketEnv = env(event, "pocket_jukebox");
        register(event, "single_disc_is_read_as_track_zero_only",
                PocketJukeboxGameTests::singleDiscIsReadAsTrackZeroOnly, pocketEnv, 200);
        register(event, "vanilla_disc_is_not_an_mdm_track",
                PocketJukeboxGameTests::vanillaDiscIsNotAnMdmTrack, pocketEnv, 200);
        register(event, "album_contents_become_the_track_list",
                PocketJukeboxGameTests::albumContentsBecomeTheTrackList, pocketEnv, 200);
        register(event, "radio_is_not_streamable",
                PocketJukeboxGameTests::radioIsNotStreamable, pocketEnv, 200);
        register(event, "advance_is_held_while_the_real_track_plays",
                PocketJukeboxGameTests::advanceIsHeldWhileTheRealTrackPlays, pocketEnv, 200);
        register(event, "non_streaming_assignment_never_holds_the_advance",
                PocketJukeboxGameTests::nonStreamingAssignmentNeverHoldsTheAdvance, pocketEnv, 200);
        register(event, "orphaned_voice_releases_the_hold_on_deadline",
                PocketJukeboxGameTests::orphanedVoiceReleasesTheHoldOnDeadline, pocketEnv, 200);
        register(event, "release_hands_back_the_voice_and_forgets_the_assignment",
                PocketJukeboxGameTests::releaseHandsBackTheVoiceAndForgetsTheAssignment, pocketEnv, 200);
        register(event, "finished_track_stays_known_so_chunk_resend_does_not_restart_it",
                ChunkResendRestartGameTests::finishedTrackStaysKnownSoChunkResendDoesNotRestartIt,
                env(event, "chunk_resend_registry"), 200);
        register(event, "album_mirror_keeps_its_origin_across_chunk_resend",
                ChunkResendRestartGameTests::albumMirrorKeepsItsOriginAcrossChunkResend,
                env(event, "album_mirror_resend"), 200);
        final var chunkResendPayloadEnv = env(event, "chunk_resend_payload");
        register(event, "chunk_resend_sends_no_payload_for_finished_disc",
                ChunkResendRestartGameTests::chunkResendSendsNoPayloadForFinishedDisc,
                chunkResendPayloadEnv, 200);
        register(event, "chunk_resend_sends_elapsed_offset_for_playing_disc",
                ChunkResendRestartGameTests::chunkResendSendsElapsedOffsetForPlayingDisc,
                chunkResendPayloadEnv, 200);
        register(event, "resolve_failed_survives_save_and_load",
                FailurePersistenceGameTests::resolveFailedSurvivesSaveAndLoad, empty, 200);
        register(event, "legacy_save_without_failure_tags_defaults_to_no_failure",
                FailurePersistenceGameTests::legacySaveWithoutFailureTagsDefaultsToNoFailure, empty, 200);
        register(event, "resolve_failed_clears_when_new_url_committed",
                FailurePersistenceGameTests::resolveFailedClearsWhenNewUrlCommitted, empty, 200);
        register(event, "resolve_failed_clears_when_next_resolve_starts",
                FailurePersistenceGameTests::resolveFailedClearsWhenNextResolveStarts, empty, 200);
        register(event, "resolve_failed_clears_when_input_disc_is_taken_out",
                FailurePersistenceGameTests::resolveFailedClearsWhenInputDiscIsTakenOut, empty, 200);
        register(event, "resolve_failed_clears_on_reset_to_neutral",
                FailurePersistenceGameTests::resolveFailedClearsOnResetToNeutral, empty, 200);
        register(event, "play_payload_carries_flat_mode",
                DirectionalToggleGameTests::playPayloadCarriesFlatMode, empty, 200);
        register(event, "play_payload_defaults_to_directional",
                DirectionalToggleGameTests::playPayloadDefaultsToDirectional, empty, 200);
        register(event, "play_payload_codec_keeps_directional",
                DirectionalToggleGameTests::playPayloadCodecKeepsDirectional, empty, 100);
        register(event, "nbt_round_trips_directional_and_defaults_to_true",
                DirectionalToggleGameTests::nbtRoundTripsDirectionalAndDefaultsToTrue, empty, 200);
        final var albumEnv = env(event, "album");
        register(event, "captured_vanilla_album_advances_and_repeats",
                AdditionalAdditionsAlbumGameTests::capturedVanillaAlbumAdvancesAndRepeats, albumEnv, 100);
        register(event, "album_is_accepted",
                AdditionalAdditionsAlbumGameTests::albumIsAccepted, albumEnv, 100);
        register(event, "effective_disc_is_current_album_track",
                AdditionalAdditionsAlbumGameTests::effectiveDiscIsCurrentAlbumTrack, albumEnv, 100);
        register(event, "album_advances_to_next_track",
                AdditionalAdditionsAlbumGameTests::albumAdvancesToNextTrack, albumEnv, 200);
        register(event, "next_playback_track_covers_album_advance_and_single_repeat",
                AdditionalAdditionsAlbumGameTests::nextPlaybackTrackCoversAlbumAdvanceAndSingleRepeat, albumEnv, 200);
        register(event, "album_track_and_offset_survive_reload",
                AdditionalAdditionsAlbumGameTests::albumTrackAndOffsetSurviveReload, albumEnv, 200);
        register(event, "mirror_leaves_plain_disc_jukeboxes_alone",
                AdditionalAdditionsAlbumGameTests::mirrorLeavesPlainDiscJukeboxesAlone,
                env(event, "album_mirror"), 100);
        register(event, "swapping_the_disc_mid_load_drops_the_old_load_when_it_finishes_first",
                PlaybackSessionGameTests::swappingTheDiscMidLoadDropsTheOldLoadWhenItFinishesFirst, empty, 100);
        register(event, "swapping_the_disc_mid_load_drops_the_old_load_when_it_finishes_last",
                PlaybackSessionGameTests::swappingTheDiscMidLoadDropsTheOldLoadWhenItFinishesLast, empty, 100);
        register(event, "displaced_voice_is_stopped_not_just_forgotten",
                PlaybackSessionGameTests::displacedVoiceIsStoppedNotJustForgotten, empty, 100);
        register(event, "stopping_during_load_discards_the_source_on_arrival",
                PlaybackSessionGameTests::stoppingDuringLoadDiscardsTheSourceOnArrival, empty, 100);
        register(event, "chunk_resend_keeps_playing_and_applies_the_listening_model",
                PlaybackSessionGameTests::chunkResendKeepsPlayingAndAppliesTheListeningModel, empty, 100);
        register(event, "seeking_restarts_instead_of_deduplicating",
                PlaybackSessionGameTests::seekingRestartsInsteadOfDeduplicating, empty, 100);
        register(event, "radio_reconnects_up_to_the_limit_then_gives_up_with_a_reason",
                PlaybackSessionGameTests::radioReconnectsUpToTheLimitThenGivesUpWithAReason, empty, 100);
        register(event, "a_stable_stretch_resets_the_reconnect_counter",
                PlaybackSessionGameTests::aStableStretchResetsTheReconnectCounter, empty, 100);
        register(event, "stop_all_invalidates_loads_that_never_started_playing",
                PlaybackSessionGameTests::stopAllInvalidatesLoadsThatNeverStartedPlaying, empty, 100);
        register(event, "a_chunk_resend_does_not_forget_the_duplicate_suppression",
                PlaybackSessionGameTests::aChunkResendDoesNotForgetTheDuplicateSuppression, empty, 100);
        register(event, "failures_from_a_stopped_playback_stay_silent",
                PlaybackSessionGameTests::failuresFromAStoppedPlaybackStaySilent, empty, 100);
        register(event, "an_engine_rejection_is_reported_once_until_it_actually_plays",
                PlaybackSessionGameTests::anEngineRejectionIsReportedOnceUntilItActuallyPlays, empty, 100);
        register(event, "an_engine_rejection_is_reported_again_after_playback_succeeded",
                PlaybackSessionGameTests::anEngineRejectionIsReportedAgainAfterPlaybackSucceeded, empty, 100);
        register(event, "the_concurrency_budget_is_shared_across_key_types",
                PlaybackSessionGameTests::theConcurrencyBudgetIsSharedAcrossKeyTypes, empty, 100);
        register(event, "shared_sweeping_does_not_disturb_a_living_jukebox",
                PlaybackSessionGameTests::sharedSweepingDoesNotDisturbALivingJukebox, empty, 100);
        register(event, "guard_reasons_map_to_kinds",
                PlaybackFailureGameTests::guardReasonsMapToKinds, empty, 100);
        register(event, "network_exceptions_are_classified_as_network",
                PlaybackFailureGameTests::networkExceptionsAreClassifiedAsNetwork, empty, 100);
        register(event, "other_exceptions_carry_their_root_cause",
                PlaybackFailureGameTests::otherExceptionsCarryTheirRootCause, empty, 100);
        register(event, "self_referencing_cause_does_not_hang",
                PlaybackFailureGameTests::selfReferencingCauseDoesNotHang, empty, 100);
        register(event, "every_kind_has_its_own_translation_key",
                PlaybackFailureGameTests::everyKindHasItsOwnTranslationKey, empty, 100);
        register(event, "every_kind_has_its_gui_label_in_every_locale",
                PlaybackFailureGameTests::everyKindHasItsGuiLabelInEveryLocale, empty, 100);
        register(event, "every_kind_has_its_own_gui_key",
                PlaybackFailureGameTests::everyKindHasItsOwnGuiKey, empty, 100);
        register(event, "jukebox_failures_are_remembered_per_position",
                PlaybackFailureGameTests::jukeboxFailuresAreRememberedPerPosition, empty, 100);
        register(event, "loader_reasons_reach_the_classification",
                PlaybackFailureGameTests::loaderReasonsReachTheClassification, empty, 100);
        register(event, "every_failure_reason_maps_to_an_actionable_kind",
                PlaybackFailureGameTests::everyFailureReasonMapsToAnActionableKind, empty, 100);
        register(event, "old_implementations_still_fail_softly",
                PlaybackFailureGameTests::oldImplementationsStillFailSoftly, empty, 100);
        register(event, "real_bot_check_message_is_classified_as_bot_check",
                PlaybackFailureGameTests::realBotCheckMessageIsClassifiedAsBotCheck, empty, 100);
        register(event, "async_fault_crosses_the_boundary",
                PlaybackFailureGameTests::asyncFaultCrossesTheBoundary, empty, 100);
        register(event, "same_failure_is_reported_only_once",
                PlaybackFailureGameTests::sameFailureIsReportedOnlyOnce, empty, 100);
        register(event, "destroying_the_block_releases_the_entry",
                ActiveDiscReleaseGameTests::destroyingTheBlockReleasesTheEntry,
                env(event, "disc_release_destroy"), 100);
        register(event, "replacing_the_block_with_air_releases_the_entry",
                ActiveDiscReleaseGameTests::replacingTheBlockWithAirReleasesTheEntry,
                env(event, "disc_release_set_block"), 100);
        register(event, "dropping_the_block_entity_alone_keeps_the_entry",
                ActiveDiscReleaseGameTests::droppingTheBlockEntityAloneKeepsTheEntry,
                env(event, "disc_release_unload"), 100);
        register(event, "speaker_link_clear_mute",
                SpeakerCoreGameTests::linksClearsAndMutesWithoutChangingTheSource, empty, 100);
        register(event, "speaker_wire_roundtrip_and_bounds",
                SpeakerWireGameTests::snapshotRoundTripAndMalformedCount, empty, 100);
        register(event, "speaker_menu_buttons_and_data_slots",
                SpeakerMenuGameTests::buttonsAndDataSlotsKeepFullVolumeRange, empty, 100);
        register(event, "speaker_menu_rejects_stale_remote_and_cross_dimension",
                SpeakerMenuGameTests::rejectsWrongMenuDistanceReplacementAndDimension, empty, 100);
        register(event, "disc_dyeing_preview_uses_result",
                DiscDyeingPreviewGameTests::previewUsesDyedResultWithoutChangingInputMetadata, empty, 100);
        register(event, "disc_dyeing_either_side_and_consumption",
                DiscDyeingPreviewGameTests::eitherDyeChangesOnlyItsRegionAndConsumesIndependently, empty, 100);
        register(event, "disc_dyeing_partial_and_shift_slots",
                DiscDyeingPreviewGameTests::undyedDiscKeepsTheMissingRegionAndShiftFillsBothDyeSlots, empty, 100);
        register(event, "speaker_wrong_dimension_preserves_item",
                SpeakerCoreGameTests::wrongDimensionLinkRejectsPlacementWithoutConsumingItem, empty, 100);
        final var speakerPlaybackEnv = env(event, "speaker_playback");
        event.registerTest(Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID,
                        "speaker_playback_routes_remote_and_diffs_updates"),
                new MethodGameTestInstance(SpeakerPlaybackGameTests::routesRemotePlayerAndDiffsSettingsAndRangeExit,
                        new TestData<>(speakerPlaybackEnv,
                                Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID, "empty64x3x8"),
                                200, 0, true)));
        event.registerTest(Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID,
                        "speaker_vanilla_migration_resend_switch"),
                new MethodGameTestInstance(SpeakerPlaybackGameTests::vanillaUsesSharedListenersForMigrationResendAndTrackSwitch,
                        new TestData<>(speakerPlaybackEnv,
                                Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID, "empty64x3x8"),
                                200, 0, true)));
        event.registerTest(Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID,
                        "speaker_vanilla_muted_initial_stops_native"),
                new MethodGameTestInstance(SpeakerPlaybackGameTests::vanillaInitialMutedSpeakerStopsNativeWithoutManagedPlay,
                        new TestData<>(speakerPlaybackEnv,
                                Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID, "empty64x3x8"),
                                200, 0, true)));
        event.registerTest(Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID,
                        "speaker_vanilla_natural_end"),
                new MethodGameTestInstance(SpeakerPlaybackGameTests::vanillaNaturalEndStopsSpeakerListener,
                        new TestData<>(speakerPlaybackEnv,
                                Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID, "empty64x3x8"),
                                200, 0, true)));
        register(event, "speaker_face_facing_states",
                SpeakerCoreGameTests::exposesAllFaceAndFacingStates, empty, 100);
        register(event, "speaker_last_item_link_place",
                SpeakerCoreGameTests::lastSpeakerItemKeepsGoldenLinkWhenPlaced, empty, 100);
        register(event, "speaker_link_volume_roundtrip",
                SpeakerCoreGameTests::linkAndVolumeSurviveBlockEntityRoundTrip, empty, 100);
        register(event, "speaker_source_identity_roundtrip",
                SpeakerCoreGameTests::sourceIdentitySurvivesItemAndSpeakerRoundTrips, empty, 100);
        register(event, "speaker_source_identity_index",
                SpeakerCoreGameTests::sourceIdentityIndexExcludesOtherAndLegacyLinks, empty, 100);
        register(event, "speaker_source_lookup_move_reuse_conflict",
                SpeakerCoreGameTests::sourceAwareLookupPromotesMovesRejectsReuseAndConflict, empty, 100);
        register(event, "speaker_destroyed_source_replacement",
                SpeakerCoreGameTests::destroyedSourceRelinksReplacementAtSamePosition, empty, 100);
        register(event, "speaker_removed_source_and_revival",
                SpeakerCoreGameTests::removedSourceDoesNotRelinkAndRevivalClearsRetirement, empty, 100);
        register(event, "speaker_late_receiver_successor",
                SpeakerCoreGameTests::lateLegacySpeakerFollowsClaimedSuccessorNotOldPosition, empty, 100);
        register(event, "golden_audio_offset_survives_slow_ticks",
                GoldenPlaybackStateGameTests::slowTicksKeepAudioOffsetForSpeakersAndReload, empty, 100);
        register(event, "speaker_fence_support",
                SpeakerCoreGameTests::fenceSupportsPlacementAndRemovalStillDropsSpeaker, empty, 100);
        register(event, "speaker_support_break_removes_index",
                SpeakerCoreGameTests::breakingEverySupportFaceRemovesSpeakerAndIndex, empty, 100);
        register(event, "speaker_stale_be_excluded",
                SpeakerCoreGameTests::staleBlockEntityAtTheSamePositionIsExcluded, empty, 100);
        register(event, "speaker_unloaded_chunk_not_loaded",
                SpeakerCoreGameTests::unloadedSpeakerIsDroppedWithoutLoadingItsChunk, empty, 100);
        register(event, "golden_redstone_comparator_and_signals_follow_unified_contract",
                GoldenRedstoneGameTests::comparatorAndSignalsFollowUnifiedContract, empty, 400);
        register(event, "golden_redstone_power_owns_only_its_pause_and_does_not_lock_the_hopper",
                GoldenRedstoneGameTests::powerOwnsOnlyItsPauseAndDoesNotLockTheHopper, empty, 400);
        register(event, "golden_redstone_top_input_moves_a_to_chest_while_b_plays",
                GoldenRedstoneGameTests::topInputMovesAToChestWhileBPlays, empty, 400);
        register(event, "golden_redstone_side_input_moves_a_to_chest_while_b_plays",
                GoldenRedstoneGameTests::sideInputMovesAToChestWhileBPlays, empty, 400);
        register(event, "golden_redstone_pause_stays_in_real_output_hopper",
                GoldenRedstoneGameTests::pauseStaysInRealOutputHopper, empty, 400);
        register(event, "golden_redstone_repeat_radio_and_unknown_stay_inserted",
                GoldenRedstoneGameTests::repeatRadioAndUnknownStayInserted, empty, 400);
        register(event, "golden_redstone_mdm_album_advances_without_extraction",
                GoldenRedstoneGameTests::mdmAlbumAdvancesWithoutExtraction, empty, 400);
        register(event, "golden_redstone_redstone_and_manual_pause_ownership_survive_reload",
                GoldenRedstoneGameTests::redstoneAndManualPauseOwnershipSurviveReload, empty, 400);
        register(event, "golden_redstone_full_output_hopper_releases_finished_disc_exactly_once",
                GoldenRedstoneGameTests::fullOutputHopperReleasesFinishedDiscExactlyOnce, empty, 400);
        register(event, "golden_redstone_restored_stopped_state_is_not_natural_completion",
                GoldenRedstoneGameTests::restoredStoppedStateIsNotNaturalCompletion, empty, 400);
        register(event, "golden_redstone_natural_completion_survives_reload_and_extracts",
                GoldenRedstoneGameTests::naturalCompletionSurvivesReloadAndExtracts, empty, 400);
        register(event, "golden_redstone_vanilla_jukebox_song_naturally_ends_and_extracts",
                GoldenRedstoneGameTests::vanillaJukeboxSongNaturallyEndsAndExtracts, empty, 520);
        register(event, "speaker_reload_reindexes_and_defaults_volume",
                SpeakerCoreGameTests::reloadReindexesLinkAndDefaultsMissingVolume, empty, 100);
    }

    //? if >=26.1 {
    private static void register(RegisterGameTestsEvent event, String name,
            java.util.function.Consumer<net.minecraft.gametest.framework.GameTestHelper> method,
            Holder<TestEnvironmentDefinition<?>> environment, int maxTicks) {
    //?} else {
    /*private static void register(RegisterGameTestsEvent event, String name,
            java.util.function.Consumer<net.minecraft.gametest.framework.GameTestHelper> method,
            Holder<TestEnvironmentDefinition> environment, int maxTicks) {
    *///?}
        event.registerTest(
                Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID, name),
                new MethodGameTestInstance(method,
                        new TestData<>(environment,
                                Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID, "empty8x3x8"),
                                maxTicks, 0, true)));
    }
}
//?} else {
//?}









