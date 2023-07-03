// Copyright 2023 The Cobalt Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include "testing/gtest/include/gtest/gtest.h"
#include "testing/gmock/include/gmock/gmock.h"

#include "cobalt/loader/image/webp_image_decoder.h"
#include "cobalt/render_tree/mock_resource_provider.h"
#include "cobalt/render_tree/resource_provider_stub.h"

using ::testing::Invoke;
using ::testing::Return;
using ::testing::_;

using cobalt::render_tree::PixelFormat;
using cobalt::render_tree::AlphaFormat;

using cobalt::math::Size;

namespace cobalt {
namespace loader {
namespace image {

class WebPDecoderTest : public testing::Test {
    void SetUp() override {
        EXPECT_CALL(resource_provider_,
            PixelFormatSupported(PixelFormat::kPixelFormatRGBA8))
            .WillRepeatedly(Return(true));
   }
    void TearDown() override {
    }
protected:
    auto make_decoder() {
        return std::make_unique<WEBPImageDecoder>(&resource_provider_, debugger_hooks_);    
    }
    static auto make_image_data(Size sz,PixelFormat pf,AlphaFormat af) {
        return new render_tree::ImageDataStub(sz,pf,af);
    }
    void expect_still_image(Size sz, AlphaFormat af) {
        EXPECT_CALL(resource_provider_,
            AlphaFormatSupported(_))
            .WillRepeatedly(Return(true));
        EXPECT_CALL(resource_provider_,
            AllocateImageDataMock(sz, PixelFormat::kPixelFormatRGBA8,af)
        ).WillOnce(Invoke(make_image_data));
    }
    base::NullDebuggerHooks debugger_hooks_;
    ::testing::StrictMock<render_tree::MockResourceProvider>
        resource_provider_;

};

TEST_F(WebPDecoderTest, ConstructDestruct) {
    EXPECT_NE(nullptr,make_decoder());;
}

TEST_F(WebPDecoderTest, InitialState) {
    EXPECT_EQ(make_decoder()->state(), ImageDataDecoder::kWaitingForHeader);
}

TEST_F(WebPDecoderTest, GetTypeString) {
    ASSERT_EQ(make_decoder()->GetTypeString(), "WEBPImageDecoder");
}

TEST_F(WebPDecoderTest, DecodeNullChunkDoesNotCrash) {
    make_decoder()->DecodeChunk(nullptr, 0);
}

// Death tests not supported yet
#if 0
TEST_F(WebPDecoderTest, DecodeNullChunkWithSize) {
    EXPECT_DEATH({
        make_decoder()->DecodeChunk(nullptr, 1);
    },"error");
}
#endif

TEST_F(WebPDecoderTest, DecodeInsufficientChunk) {
    const uint8_t chunk[] = {0x0};
    auto decoder = make_decoder();
    decoder->DecodeChunk( chunk, 1);
    EXPECT_EQ(decoder->state(), ImageDataDecoder::kWaitingForHeader);
}

TEST_F(WebPDecoderTest, DecodeInvalidHeaderChunk) {
    const uint8_t chunk[12] = {0x0};
    auto decoder = make_decoder();
    decoder->DecodeChunk( chunk, sizeof(chunk));
    EXPECT_EQ(decoder->state(), ImageDataDecoder::kError);
}

TEST_F(WebPDecoderTest, DecodeValidStillHeader) {
    const uint8_t chunk[] = 
        "RIFF\x92`\x00\x00WEBPVP8 " "\x86\x60\x00\x00" 
        "\xf0\x84\x01\x9d" "\x01\x2a\xe0\x01\x68";
    expect_still_image(Size(480,104), AlphaFormat::kAlphaFormatOpaque);
    auto decoder = make_decoder();
    decoder->DecodeChunk( chunk, sizeof(chunk));
    EXPECT_EQ(decoder->state(), ImageDataDecoder::kReadLines);
    EXPECT_EQ(nullptr, decoder->FinishAndMaybeReturnImage());
}

TEST_F(WebPDecoderTest, DecodeValidStillHeaderWithAlpha) {
    const uint8_t chunk[] = 
        "RIFF\xbcr\x00\x00WEBPVP8X" "\x0a\x00\x00\x00"
        "\x10\x00\x00\x00" "\xdf\x01\x00\xff\x01";
    expect_still_image(Size(480,512), AlphaFormat::kAlphaFormatPremultiplied);
    auto decoder = make_decoder();
    decoder->DecodeChunk( chunk, sizeof(chunk));
    EXPECT_EQ(decoder->state(), ImageDataDecoder::kReadLines);
    EXPECT_EQ(nullptr, decoder->FinishAndMaybeReturnImage());
}

TEST_F(WebPDecoderTest, DecodeValidAnimatedHeader) {
    const uint8_t chunk[] = "RIFF`Q\x09\x00WEBPVP8X"
        "\x0a\x00\x00\x00" "\x12\x00\x00\x00"
        "\xff\x01\x00\xff\x01";
    auto decoder = make_decoder();
    decoder->DecodeChunk( chunk, sizeof(chunk));
    EXPECT_EQ(decoder->state(), ImageDataDecoder::kReadLines);
    auto image = decoder->FinishAndMaybeReturnImage();
    EXPECT_TRUE(image->IsAnimated());
    EXPECT_EQ(image->GetSize(), Size(512,512));
    EXPECT_TRUE(image->IsOpaque());
}

TEST_F(WebPDecoderTest, DecodeValidAnimatedHeaderWithAlpha) {
    const uint8_t chunk[] = "RIFFZ41.WEBPVP8X"
        "\x0a\x00\x00\x00" "\x12\x00\x00\x00"
        "\xdf\x01\x00\x0d\x01\x00";
    auto decoder = make_decoder();
    decoder->DecodeChunk( chunk, sizeof(chunk));
    EXPECT_EQ(decoder->state(), ImageDataDecoder::kReadLines);
    auto image = decoder->FinishAndMaybeReturnImage();
    EXPECT_TRUE(image->IsAnimated());
    EXPECT_EQ(image->GetSize(), Size(480,270));
    EXPECT_TRUE(image->IsOpaque());

    auto webp = base::polymorphic_downcast<AnimatedWebPImage*>(image.get());
    EXPECT_NE(nullptr, webp);
    ASSERT_EQ(webp->GetFrameCount(), 0);
}

}}}