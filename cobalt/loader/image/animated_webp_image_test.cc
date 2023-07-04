// Copyright 2015 The Cobalt Authors. All Rights Reserved.
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

#include <memory>
#include <string>
#include <vector>

#include "testing/gtest/include/gtest/gtest.h"

#include "cobalt/loader/image/animated_webp_image.h"
#include "cobalt/render_tree/resource_provider_stub.h"
#include "cobalt/base/debugger_hooks.h"
#include "base/task/task_traits.h"
#include "base/task/post_task.h"
#include "base/sequenced_task_runner.h"
#include "base/time/time.h"
#include "base/threading/thread_task_runner_handle.h"

#include "cobalt/render_tree/mock_resource_provider.h"

#include "base/test/scoped_task_environment.h"
#include "base/test/test_mock_time_task_runner.h"
#include "cobalt/loader/image/image_decoder_mock.h"

#include "base/path_service.h"
#include "base/files/file_util.h"

#include "base/test/scoped_mock_clock_override.h"

namespace cobalt {
namespace loader {
namespace image {


// copypaste
namespace {

base::FilePath GetTestImagePath(const char* file_name) {
  base::FilePath data_directory;
  CHECK(base::PathService::Get(base::DIR_TEST_DATA, &data_directory));
  return data_directory.Append(FILE_PATH_LITERAL("cobalt"))
      .Append(FILE_PATH_LITERAL("loader"))
      .Append(FILE_PATH_LITERAL("testdata"))
      .Append(FILE_PATH_LITERAL(file_name));
}


class FakeResourceProviderStub : public render_tree::ResourceProviderStub {
  base::TypeId GetTypeId() const override {
    return base::GetTypeId<FakeResourceProviderStub>();
  }
};


/*
void TestFile(MediaContainerName expected, const base::FilePath& filename) {
  char buffer[8192];

  // Windows implementation of ReadFile fails if file smaller than desired size,
  // so use file length if file less than 8192 bytes (http://crbug.com/243885).
  int read_size = sizeof(buffer);
  int64_t actual_size;
  if (base::GetFileSize(filename, &actual_size) && actual_size < read_size)
    read_size = actual_size;
  int read = base::ReadFile(filename, buffer, read_size);

  // Now verify the type.
  EXPECT_EQ(expected,
            DetermineContainer(reinterpret_cast<const uint8_t*>(buffer), read))
      << "Failure with file " << filename.value();
}
*/

std::vector<uint8> GetImageData(const base::FilePath& file_path) {
  int64 size;
  std::vector<uint8> image_data;

  bool success = base::GetFileSize(file_path, &size);

  CHECK(success) << "Could not get file size.";
  CHECK_GT(size, 0);

  image_data.resize(static_cast<size_t>(size));

  int num_of_bytes =
      base::ReadFile(file_path, reinterpret_cast<char*>(&image_data[0]),
                     static_cast<int>(size));

  CHECK_EQ(num_of_bytes, static_cast<int>(image_data.size()))
      << "Could not read '" << file_path.value() << "'.";
  return image_data;
}

// end copypaste

}

using ::testing::Invoke;
using ::testing::Return;
using ::testing::_;

using cobalt::math::Size;
using cobalt::render_tree::PixelFormat;
using cobalt::render_tree::AlphaFormat;

class AnimatedWebPTest : public testing::Test {
public:
    AnimatedWebPTest() : image_decoder(&resource_provider_) {}
    void SetUp() override {
      task_runner_ = base::ThreadTaskRunnerHandle::Get();
      //task_runner_ = base::CreateSequencedTaskRunnerWithTraits({});
      //task_runner_ = base::MakeRefCounted<base::TestMockTimeTaskRunner>();
      //const scoped_refptr<base::SequencedTaskRunner> background_task_runner_ =
      //  base::CreateSequencedTaskRunnerWithTraits({base::MayBlock()});
    }
    void TearDown() override {
    }
    protected:
    static auto make_image_data(Size sz,PixelFormat pf,AlphaFormat af) {
        return new render_tree::ImageDataStub(sz,pf,af);
    }
    static auto make_image(render_tree::ImageData* pixel_data) {
      auto desc = pixel_data->GetDescriptor();
      return new render_tree::ImageStub(
        std::unique_ptr<render_tree::ImageDataStub>(make_image_data(
                  desc.size, desc.pixel_format, desc.alpha_format
                  ))        
      );
    }
    void expect_image_data(Size sz, AlphaFormat af) {
        EXPECT_CALL(resource_provider_,
            AllocateImageDataMock(sz, PixelFormat::kPixelFormatRGBA8,af)
        ).WillOnce(Invoke(make_image_data));
    }
    void expect_still_image(Size sz, AlphaFormat af) {
        EXPECT_CALL(resource_provider_,
            AlphaFormatSupported(_))
            .WillRepeatedly(Return(true));
        expect_image_data(sz,af);
    }
    void advance(int64_t t) {
      // Mock clock needs to be forwarded before taskrunner
      AdvanceClockMs(t);
      AdvanceTasksMs(t);
    }
    void AdvanceClockMs(int64_t t) {
      mock_clock.Advance(base::TimeDelta::FromMilliseconds(t));
    }
    void AdvanceTasksMs(int64_t t) {
      scoped_task_environment_.FastForwardBy(base::TimeDelta::FromMilliseconds(t));
    }
    void AdvanceTimeMs(int64_t t) {
      LOG(WARNING) << "Advancing time by " << t;
      AdvanceClockMs(t);
      AdvanceTasksMs(t);
    }

    AnimatedWebPImage* image_from_file(const char * file) {
      //MockImageDecoder image_decoder(&resource_provider_);
      image_decoder.ExpectCallWithError(base::nullopt);
      std::vector<uint8> image_data = GetImageData(GetTestImagePath(file));
      EXPECT_CALL(resource_provider_,
                PixelFormatSupported(render_tree::PixelFormat::kPixelFormatRGBA8))
          .WillRepeatedly(Return(true));
      image_decoder.DecodeChunk(reinterpret_cast<char*>(&image_data[0]), image_data.size());
      image_decoder.Finish();
      return base::polymorphic_downcast<AnimatedWebPImage*>(image_decoder.image().get());
    }
    void expect_frame(int w, int h) {
      EXPECT_CALL(resource_provider_,
          AllocateImageDataMock(Size(w,h), PixelFormat::kPixelFormatRGBA8,AlphaFormat::kAlphaFormatOpaque)
      ).WillOnce(Invoke(make_image_data));
      EXPECT_CALL(resource_provider_,
        CreateImageMock(_)
      ).WillOnce(Invoke(make_image));
      EXPECT_CALL(resource_provider_,
        DrawOffscreenImage(_)
      );
    }

    ::testing::StrictMock<cobalt::render_tree::MockResourceProvider>
      resource_provider_;
    base::NullDebuggerHooks debugger_hooks_;

    base::ScopedMockClockOverride mock_clock;
    base::test::ScopedTaskEnvironment scoped_task_environment_{
        base::test::ScopedTaskEnvironment::MainThreadType::MOCK_TIME};
    scoped_refptr<base::SequencedTaskRunner> task_runner_;
    //scoped_refptr<base::TestMockTimeTaskRunner> task_runner_;

    MockImageDecoder image_decoder;
private:
    DISALLOW_COPY_AND_ASSIGN(AnimatedWebPTest);

};

TEST_F(AnimatedWebPTest, BasicTasks) {
    auto f = base::MakeRefCounted<AnimatedWebPImage>(math::Size(1, 1), false, &resource_provider_, debugger_hooks_);
    f->SetupTaskRunner(task_runner_);
    //advance(15);
    f->MakeDelayedTask(10);
    advance(5);
    LOG(WARNING) << "Nothing happens here";
    //advance(15);
    //task_runner_->RunUntilIdle();
    //scoped_task_environment_.RunUntilIdle();
    scoped_task_environment_.FastForwardUntilNoTasksRemain();
}

TEST_F(AnimatedWebPTest, SingleFrame) {
    scoped_refptr<AnimatedWebPImage> f = new AnimatedWebPImage(math::Size(-1, -1), false, &resource_provider_, debugger_hooks_);
    auto image_data = GetImageData(GetTestImagePath("webp_image.webp"));
    f->AppendChunk(&image_data[0], image_data.size());
    ASSERT_EQ(f->GetLoopCount(),1);
    ASSERT_EQ(f->GetFrameCount(),1);
}

TEST_F(AnimatedWebPTest, Animated) {
    scoped_refptr<AnimatedWebPImage> f = new AnimatedWebPImage(math::Size(1, 1), false, &resource_provider_, debugger_hooks_);
    auto image_data = GetImageData(GetTestImagePath("vsauce_sm.webp"));
    f->AppendChunk(&image_data[0], image_data.size());
    ASSERT_EQ(f->GetLoopCount(),0);
    ASSERT_EQ(f->GetFrameCount(),57);
}


TEST_F(AnimatedWebPTest, AnimatedPlay) {
    scoped_task_environment_.FastForwardBy(base::TimeDelta::FromMilliseconds(1));
    auto f = base::MakeRefCounted<AnimatedWebPImage>(math::Size(1, 1), false, &resource_provider_, debugger_hooks_);

    auto image_data = GetImageData(GetTestImagePath("vsauce_sm.webp"));
    EXPECT_CALL(resource_provider_,
              PixelFormatSupported(render_tree::PixelFormat::kPixelFormatRGBA8))
        .WillRepeatedly(Return(true));
    EXPECT_CALL(resource_provider_,
              AlphaFormatSupported(render_tree::AlphaFormat::kAlphaFormatPremultiplied))
        .WillRepeatedly(Return(true));

    expect_still_image(Size(480, 270), AlphaFormat::kAlphaFormatOpaque);

    auto image_stub = base::MakeRefCounted<render_tree::ImageStub>(
        std::unique_ptr<render_tree::ImageDataStub>(
          make_image_data(Size(480,270), 
                    PixelFormat::kPixelFormatRGBA8,
                    AlphaFormat::kAlphaFormatOpaque
                    )          
        )
      );
    EXPECT_CALL(resource_provider_,
      CreateImageMock(_)
    ).WillOnce(Return(image_stub));

    f->AppendChunk(&image_data[0], image_data.size());
    ASSERT_EQ(f->GetCurrentFrameIndex(),0);
    ASSERT_EQ(f->GetLoopCount(),0);
    ASSERT_EQ(f->GetFrameCount(),57);
    f->Play(task_runner_);
    advance(10);
    

    for(auto i=0; i < 50;i++) {
      LOG(WARNING) << "===FastForward" << i;
      expect_image_data(Size(480, 270), AlphaFormat::kAlphaFormatOpaque);
      advance(120);
      f->GetFrameProvider()->GetFrame();
    }

    LOG(WARNING) << "===RunUntilIlde";
    //scoped_task_environment_.RunUntilIdle();
    // Cant do that, it loops
    //scoped_task_environment_.FastForwardUntilNoTasksRemain();

    ASSERT_EQ(f->GetCurrentFrameIndex(),57);
}

TEST_F(AnimatedWebPTest, Animated4) {
  AdvanceTimeMs(9);

  auto animation = image_from_file("webp-animated-opaque.webp");

  ASSERT_EQ(animation->GetLoopCount(),0);
  ASSERT_EQ(animation->GetFrameCount(),4);
  ASSERT_EQ(animation->GetCurrentFrameIndex(),0);

  animation->Play(task_runner_);
  ASSERT_EQ(animation->GetCurrentFrameIndex(),0);

  EXPECT_CALL(resource_provider_,
      AlphaFormatSupported(_))
      .WillRepeatedly(Return(true));

  auto consume_frame = [&](int w,int h) {
    animation->GetFrameProvider()->GetFrame();
    expect_frame(w,h);
  };

  consume_frame(33,32);
  AdvanceTimeMs(1);
  ASSERT_EQ(animation->GetCurrentFrameIndex(),1);
  AdvanceTimeMs(999);
  ASSERT_EQ(animation->GetCurrentFrameIndex(),1);
  AdvanceTimeMs(1);

  consume_frame(33,32);
  AdvanceTimeMs(100);
  ASSERT_EQ(animation->GetCurrentFrameIndex(),2);

  consume_frame(32,32);
  AdvanceTimeMs(900);
  ASSERT_EQ(animation->GetCurrentFrameIndex(),3);

  consume_frame(32,33);
  AdvanceTimeMs(1000);
  // Expect looping back to first frame
  ASSERT_EQ(animation->GetCurrentFrameIndex(),0);
}

} // namespace image

} // namespace loader

} // namespace cobalt
