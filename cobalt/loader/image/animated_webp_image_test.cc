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


#include "base/path_service.h"
#include "base/files/file_util.h"

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

class AnimatedWebPTest : public testing::Test {
    void SetUp() override {
      task_runner_ = base::ThreadTaskRunnerHandle::Get();
    }
    void TearDown() override {
    }
    protected:
    base::test::ScopedTaskEnvironment scoped_task_environment_{
        base::test::ScopedTaskEnvironment::MainThreadType::MOCK_TIME};
    scoped_refptr<base::SequencedTaskRunner> task_runner_;

    base::NullDebuggerHooks debugger_hooks_;
    ::testing::NiceMock<cobalt::render_tree::MockResourceProvider>
      resource_provider_;
};

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

using ::testing::Return;

class FakeImageData : public render_tree::ImageData {
public:
  virtual const render_tree::ImageDataDescriptor& GetDescriptor() const {
    return desc;
  }
  uint8_t * GetMemory() { return buffer_; }
private:
  uint8_t buffer_[1024];
  render_tree::ImageDataDescriptor desc{math::Size(1,1), 
        render_tree::PixelFormat::kPixelFormatRGBA8,
        render_tree::AlphaFormat::kAlphaFormatOpaque,
        2};
};

TEST_F(AnimatedWebPTest, AnimatedPlay) {
    scoped_refptr<AnimatedWebPImage> f = new AnimatedWebPImage(math::Size(1, 1), false, &resource_provider_, debugger_hooks_);

    auto image_data = GetImageData(GetTestImagePath("vsauce_sm.webp"));
    EXPECT_CALL(resource_provider_,
              PixelFormatSupported(render_tree::PixelFormat::kPixelFormatRGBA8))
        .WillRepeatedly(::testing::Return(true));
    EXPECT_CALL(resource_provider_,
              AlphaFormatSupported(render_tree::AlphaFormat::kAlphaFormatOpaque))
        .WillRepeatedly(::testing::Return(true));
    EXPECT_CALL(resource_provider_,
              AlphaFormatSupported(render_tree::AlphaFormat::kAlphaFormatPremultiplied))
        .WillRepeatedly(::testing::Return(true));

    std::unique_ptr<render_tree::ImageDataStub> data = std::make_unique<render_tree::ImageDataStub>(
      math::Size(480,270),
      render_tree::PixelFormat::kPixelFormatRGBA8,
      render_tree::AlphaFormat::kAlphaFormatOpaque
     );
    std::unique_ptr<render_tree::ImageDataStub> data2 = std::make_unique<render_tree::ImageDataStub>(
      math::Size(480,270),
      render_tree::PixelFormat::kPixelFormatRGBA8,
      render_tree::AlphaFormat::kAlphaFormatOpaque
     );
    EXPECT_CALL(resource_provider_,
        AllocateImageDataMock(testing::_,testing::_,testing::_)
      ).WillRepeatedly(Return(data2.get()));

    scoped_refptr<render_tree::ImageStub> image_stub = 
      new render_tree::ImageStub(std::move(data));
    EXPECT_CALL(resource_provider_,
      CreateImageMock(testing::_)
    ).WillOnce(Return(image_stub));

    f->AppendChunk(&image_data[0], image_data.size());
    ASSERT_EQ(f->GetCurrentFrameIndex(),0);
    ASSERT_EQ(f->GetLoopCount(),0);
    ASSERT_EQ(f->GetFrameCount(),57);
    f->Play(task_runner_);
    
    scoped_task_environment_.FastForwardBy(base::TimeDelta::FromMilliseconds(100));
    scoped_task_environment_.RunUntilIdle();
    ASSERT_EQ(f->GetCurrentFrameIndex(),57);

    data->ReleaseMemory();
    data2->ReleaseMemory();
}

} // namespace image

} // namespace loader

} // namespace cobalt
