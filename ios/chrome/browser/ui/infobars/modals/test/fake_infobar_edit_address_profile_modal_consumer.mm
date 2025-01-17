// Copyright 2021 The Chromium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#import "ios/chrome/browser/ui/infobars/modals/test/fake_infobar_edit_address_profile_modal_consumer.h"

#if !defined(__has_feature) || !__has_feature(objc_arc)
#error "This file requires ARC support."
#endif

@implementation FakeInfobarEditAddressProfileModalConsumer
- (void)setupModalViewControllerWithData:(NSDictionary*)data {
  self.profileData = [NSMutableDictionary dictionaryWithDictionary:data];
}

- (void)setIsEditForUpdate:(BOOL)isEditForUpdate {
}

- (void)setMigrationPrompt:(BOOL)migrationPrompt {
}

@end
